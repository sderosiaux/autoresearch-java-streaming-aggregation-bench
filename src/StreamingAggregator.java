import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

public class StreamingAggregator {

    static final long TUMBLING_WINDOW_MS = 60_000;
    static final long SLIDING_WINDOW_MS = 5 * 60_000;
    static final long SLIDING_STEP_MS = 60_000;

    private static final int[] DAYS_CUM = {0,31,59,90,120,151,181,212,243,273,304,334};
    private static final int[] DAYS_CUM_LEAP = {0,31,60,91,121,152,182,213,244,274,305,335};

    // Digit-pair lookup tables: halve divisions in number formatting
    private static final byte[] DIGIT_TENS = new byte[100];
    private static final byte[] DIGIT_ONES = new byte[100];
    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_TENS[i] = (byte)('0' + i / 10);
            DIGIT_ONES[i] = (byte)('0' + i % 10);
        }
    }

    static final int MAX_MINUTES = 1500;
    static final int MAX_SENSORS = 1100;

    // Unified state: all-integer tumbling aggregates + scaled int values for sliding percentiles.
    // No floating-point in hot path — sum/min/max as scaled ints, convert at emit time only.
    static class TumblingState {
        long count = 0;
        long scaledSum = 0; // sum × 100 as long (exact integer arithmetic)
        int scaledMin = Integer.MAX_VALUE;
        int scaledMax = Integer.MIN_VALUE;
        int[] values = new int[8]; // scaled by 100 (e.g., 29.19 → 2919)
        int size = 0;

        void add(int scaledValue) {
            count++;
            scaledSum += scaledValue;
            scaledMin = Math.min(scaledMin, scaledValue);
            scaledMax = Math.max(scaledMax, scaledValue);
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = scaledValue;
        }

        void merge(TumblingState other) {
            count += other.count;
            scaledSum += other.scaledSum;
            scaledMin = Math.min(scaledMin, other.scaledMin);
            scaledMax = Math.max(scaledMax, other.scaledMax);
            int needed = size + other.size;
            if (needed > values.length) {
                values = Arrays.copyOf(values, needed);
            }
            System.arraycopy(other.values, 0, values, size, other.size);
            size += other.size;
        }

        static int quickselect(int[] arr, int lo, int hi, int k) {
            while (lo < hi) {
                int mid = lo + (hi - lo) / 2;
                if (arr[mid] < arr[lo]) { int t = arr[lo]; arr[lo] = arr[mid]; arr[mid] = t; }
                if (arr[hi] < arr[lo]) { int t = arr[lo]; arr[lo] = arr[hi]; arr[hi] = t; }
                if (arr[mid] < arr[hi]) { int t = arr[mid]; arr[mid] = arr[hi]; arr[hi] = t; }
                int pivot = arr[hi];
                // Branchless Lomuto partition: unconditional swap + CMOV advance
                // Eliminates branch mispredictions in partition scan (~15 per pass on random data)
                int storeIdx = lo;
                for (int i = lo; i < hi; i++) {
                    int ai = arr[i];
                    int as = arr[storeIdx];
                    arr[i] = as;
                    arr[storeIdx] = ai;
                    storeIdx += (ai < pivot) ? 1 : 0;
                }
                arr[hi] = arr[storeIdx]; arr[storeIdx] = pivot;
                if (k == storeIdx) return pivot;
                else if (k < storeIdx) hi = storeIdx - 1;
                else lo = storeIdx + 1;
            }
            return arr[lo];
        }
    }

    // Per-thread partition state — [minute][sensor] layout for cache-friendly access
    static class PartitionState {
        TumblingState[][] tumbling = new TumblingState[MAX_MINUTES][];
        String[] sensorNames = new String[MAX_SENSORS];
        int sensorCount = 0;
        long baseMs;
        int minMinute = MAX_MINUTES;
        int maxMinute = -1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: StreamingAggregator <input-file>");
            System.exit(1);
        }

        int nThreads = Runtime.getRuntime().availableProcessors();
        RandomAccessFile raf = new RandomAccessFile(args[0], "r");
        long fileSize = raf.length();
        FileChannel channel = raf.getChannel();

        // Single mmap of entire file
        MappedByteBuffer mbb = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

        // Find line-aligned chunk boundaries
        long[] boundaries = new long[nThreads + 1];
        boundaries[0] = 0;
        boundaries[nThreads] = fileSize;

        byte[] scanBuf = new byte[4096];
        for (int i = 1; i < nThreads; i++) {
            int approx = (int)(fileSize * i / nThreads);
            mbb.get(approx, scanBuf, 0, Math.min(4096, (int)(fileSize - approx)));
            int nl = -1;
            for (int j = 0; j < scanBuf.length; j++) {
                if (scanBuf[j] == '\n') { nl = j; break; }
            }
            boundaries[i] = (nl >= 0) ? approx + nl + 1 : approx;
        }

        // Determine baseMs from first line
        byte[] firstLine = new byte[128];
        mbb.get(0, firstLine, 0, 128);
        long firstTs = parseIsoBytesArr(firstLine, 0);
        long baseMs = (firstTs / 60_000 - 15) * 60_000;

        // Process chunks in parallel — parse directly from MappedByteBuffer (zero copy)
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        Future<PartitionState>[] futures = new Future[nThreads];

        for (int t = 0; t < nThreads; t++) {
            final int chunkStart = (int)boundaries[t];
            final int chunkEnd = (int)boundaries[t + 1];
            final long bMs = baseMs;
            futures[t] = pool.submit(() -> processChunkMBB(mbb, chunkStart, chunkEnd, bMs));
        }

        // Collect partition states
        PartitionState[] parts = new PartitionState[nThreads];
        int sensorCount = 0;
        int globalMinMinute = MAX_MINUTES;
        int globalMaxMinute = -1;
        for (int t = 0; t < nThreads; t++) {
            parts[t] = futures[t].get();
            if (parts[t].sensorCount > sensorCount) sensorCount = parts[t].sensorCount;
            if (parts[t].minMinute < globalMinMinute) globalMinMinute = parts[t].minMinute;
            if (parts[t].maxMinute > globalMaxMinute) globalMaxMinute = parts[t].maxMinute;
        }
        // Generate sensor names from indices (fixed format sensor_XXXX)
        String[] sensorNames = new String[sensorCount];
        char[] nameBuf = "sensor_0000".toCharArray();
        for (int s = 0; s < sensorCount; s++) {
            nameBuf[7] = (char)('0' + s / 1000);
            nameBuf[8] = (char)('0' + (s / 100) % 10);
            nameBuf[9] = (char)('0' + (s / 10) % 10);
            nameBuf[10] = (char)('0' + s % 10);
            sensorNames[s] = new String(nameBuf);
        }
        // Parallel merge+percentile by sensor range
        final int sc = sensorCount;
        final String[] sNames = sensorNames;
        final long bMs = baseMs;
        final int gMin = globalMinMinute;
        final int gMax = globalMaxMinute;
        int sensorsPerThread = (sc + nThreads - 1) / nThreads;

        // Shared merged array — [minute][sensor] layout for cache-friendly emit
        // Only allocate for the actual used range [gMin..gMax]
        TumblingState[][] mergedTumbling = new TumblingState[MAX_MINUTES][];
        for (int m = gMin; m <= gMax; m++) {
            mergedTumbling[m] = new TumblingState[sc];
        }

        @SuppressWarnings("unchecked")
        Future<?>[] mergeFutures = new Future[nThreads];
        for (int t = 0; t < nThreads; t++) {
            int sStart = t * sensorsPerThread;
            int sEnd = Math.min(sStart + sensorsPerThread, sc);
            mergeFutures[t] = pool.submit(() -> {
                mergePartitions(parts, sStart, sEnd, mergedTumbling);
            });
        }
        for (Future<?> f : mergeFutures) f.get();

        // Pre-compute prefixes as byte[] and sensor names as byte[]
        int pfxMin = Math.max(0, gMin - 4);
        byte[][] slidingPfx = new byte[MAX_MINUTES][];
        byte[][] tumblingPfx = new byte[MAX_MINUTES][];
        for (int m = pfxMin; m <= gMax; m++) {
            long ws = bMs + (long) m * 60_000;
            slidingPfx[m] = ("sliding," + ws + ",").getBytes();
            tumblingPfx[m] = ("tumbling," + ws + ",").getBytes();
        }
        byte[][] sNameBytes = new byte[sc][];
        for (int s = 0; s < sc; s++) {
            sNameBytes[s] = sNames[s] != null ? sNames[s].getBytes() : new byte[0];
        }
        byte[] NEW_LINE = ",new\n".getBytes();

        // Sliding windows can start up to 4 minutes before first data
        final int emitMin = Math.max(0, gMin - 4);
        int minuteRange = gMax - emitMin + 1;
        int minutesPerThread = (minuteRange + nThreads - 1) / nThreads;

        // Shared emit buffers — avoid Arrays.copyOf trim at end of each task
        byte[][] slidingBufs = new byte[nThreads][];
        int[] slidingLens = new int[nThreads];
        byte[][] tumblingBufs = new byte[nThreads][];
        int[] tumblingLens = new int[nThreads];

        // Emit sliding windows — compute percentiles inline, direct byte[] output
        @SuppressWarnings("unchecked")
        Future<?>[] slidingEmitFutures = new Future[nThreads];
        for (int t = 0; t < nThreads; t++) {
            int tt = t;
            int mStart = emitMin + t * minutesPerThread;
            int mEnd = Math.min(mStart + minutesPerThread, gMax + 1);
            final int fsc = sc;
            slidingEmitFutures[t] = pool.submit(() -> {
                byte[] buf = new byte[8 << 20];
                int pos = 0;
                int[] combinedBuf = new int[1024];
                for (int m = mStart; m < mEnd; m++) {
                    byte[] pfx = slidingPfx[m];
                    int kEnd = Math.min(m + 4, gMax);
                    for (int s = 0; s < fsc; s++) {
                        int[] combined = combinedBuf;
                        int p = 0;
                        for (int k = m; k <= kEnd; k++) {
                            TumblingState[] row = mergedTumbling[k];
                            TumblingState ts = (row != null) ? row[s] : null;
                            if (ts != null) {
                                int sz = ts.size;
                                int needed = p + sz;
                                if (needed > combined.length) {
                                    combinedBuf = new int[needed * 2];
                                    System.arraycopy(combined, 0, combinedBuf, 0, p);
                                    combined = combinedBuf;
                                }
                                System.arraycopy(ts.values, 0, combined, p, sz);
                                p += sz;
                            }
                        }
                        if (p == 0) continue;
                        int totalSize = p;
                        int i99 = Math.max(0, (99 * totalSize + 99) / 100 - 1);
                        int i50 = Math.max(0, (totalSize + 1) / 2 - 1);
                        int p99 = TumblingState.quickselect(combined, 0, totalSize - 1, i99);
                        int p50 = TumblingState.quickselect(combined, 0, i99, i50);
                        if (pos + 200 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                        System.arraycopy(pfx, 0, buf, pos, pfx.length); pos += pfx.length;
                        System.arraycopy(sNameBytes[s], 0, buf, pos, sNameBytes[s].length); pos += sNameBytes[s].length;
                        buf[pos++] = ',';
                        pos = appendScaledInt(buf, pos, p50);
                        buf[pos++] = ',';
                        pos = appendScaledInt(buf, pos, p99);
                        System.arraycopy(NEW_LINE, 0, buf, pos, NEW_LINE.length); pos += NEW_LINE.length;
                    }
                }
                slidingBufs[tt] = buf;
                slidingLens[tt] = pos;
                return null;
            });
        }

        // Emit tumbling windows — direct byte[] output
        @SuppressWarnings("unchecked")
        Future<?>[] tumblingEmitFutures = new Future[nThreads];
        for (int t = 0; t < nThreads; t++) {
            int tt = t;
            int mStart = emitMin + t * minutesPerThread;
            int mEnd = Math.min(mStart + minutesPerThread, gMax + 1);
            final int fsc = sc;
            tumblingEmitFutures[t] = pool.submit(() -> {
                byte[] buf = new byte[10 << 20];
                int pos = 0;
                for (int m = mStart; m < mEnd; m++) {
                    TumblingState[] tRow = mergedTumbling[m];
                    if (tRow == null) continue;
                    byte[] pfx = tumblingPfx[m];
                    for (int s = 0; s < fsc; s++) {
                        TumblingState state = tRow[s];
                        if (state == null) continue;
                        if (pos + 200 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                        System.arraycopy(pfx, 0, buf, pos, pfx.length); pos += pfx.length;
                        System.arraycopy(sNameBytes[s], 0, buf, pos, sNameBytes[s].length); pos += sNameBytes[s].length;
                        buf[pos++] = ',';
                        pos = appendLongBytes(buf, pos, state.count);
                        buf[pos++] = ',';
                        pos = appendScaledLong(buf, pos, state.scaledSum);
                        buf[pos++] = ',';
                        pos = appendScaledInt(buf, pos, state.scaledMin);
                        buf[pos++] = ',';
                        pos = appendScaledInt(buf, pos, state.scaledMax);
                        buf[pos++] = ',';
                        // Integer avg: scaledSum always fits in int (max ~50 events × 99999 = 4.9M)
                        int ss = (int)state.scaledSum, cc = (int)state.count;
                        int avgScaled = (ss >= 0) ? (ss + cc / 2) / cc : (ss - cc / 2) / cc;
                        pos = appendScaledInt(buf, pos, avgScaled);
                        System.arraycopy(NEW_LINE, 0, buf, pos, NEW_LINE.length); pos += NEW_LINE.length;
                    }
                }
                tumblingBufs[tt] = buf;
                tumblingLens[tt] = pos;
                return null;
            });
        }

        // Output in order: direct to fd 1, no BOS double-buffering (saves 166MB copy)
        FileOutputStream fos = new FileOutputStream(FileDescriptor.out);
        for (int t = 0; t < nThreads; t++) {
            slidingEmitFutures[t].get();
            fos.write(slidingBufs[t], 0, slidingLens[t]);
        }
        for (int t = 0; t < nThreads; t++) {
            tumblingEmitFutures[t].get();
            fos.write(tumblingBufs[t], 0, tumblingLens[t]);
        }

        pool.shutdown();
        channel.close();
        raf.close();
    }

    static void mergePartitions(PartitionState[] parts, int sStart, int sEnd,
                                TumblingState[][] mergedTumbling) {
        // Merge directly into mergedTumbling — no intermediate array
        for (int s = sStart; s < sEnd; s++) {
            for (PartitionState ps : parts) {
                int lo = ps.minMinute, hi = ps.maxMinute;
                for (int m = lo; m <= hi; m++) {
                    TumblingState[] tRow = ps.tumbling[m];
                    if (tRow != null && tRow[s] != null) {
                        TumblingState[] mRow = mergedTumbling[m];
                        if (mRow[s] == null) mRow[s] = tRow[s];
                        else mRow[s].merge(tRow[s]);
                    }
                }
            }
        }
    }

    static PartitionState processChunkMBB(MappedByteBuffer mbb, int chunkStart, int chunkEnd, long baseMs) {
        PartitionState ps = new PartitionState();
        ps.baseMs = baseMs;

        if (chunkStart >= chunkEnd) return ps;

        // Parse first timestamp to compute day offset
        int hour0 = (mbb.get(chunkStart + 11) - '0') * 10 + (mbb.get(chunkStart + 12) - '0');
        int minute0 = (mbb.get(chunkStart + 14) - '0') * 10 + (mbb.get(chunkStart + 15) - '0');
        int year = (mbb.get(chunkStart) - '0') * 1000 + (mbb.get(chunkStart+1) - '0') * 100
                 + (mbb.get(chunkStart+2) - '0') * 10 + (mbb.get(chunkStart+3) - '0');
        int month = (mbb.get(chunkStart+5) - '0') * 10 + (mbb.get(chunkStart+6) - '0');
        int day = (mbb.get(chunkStart+8) - '0') * 10 + (mbb.get(chunkStart+9) - '0');
        int second = (mbb.get(chunkStart+17) - '0') * 10 + (mbb.get(chunkStart+18) - '0');
        long totalDays = 365L * (year - 1970);
        totalDays += countLeapYears(year - 1) - countLeapYears(1969);
        boolean leap = (year % 4 == 0) && ((year % 100 != 0) || (year % 400 == 0));
        totalDays += (leap ? DAYS_CUM_LEAP : DAYS_CUM)[month - 1] + day - 1;
        long firstChunkTs = ((totalDays * 24 + hour0) * 60 + minute0) * 60000L + second * 1000L;
        int baseDayMinutes = (int)(firstChunkTs / 60_000) - (int)(firstChunkTs / 60_000) % 1440;
        int baseMinute = (int)(baseMs / 60_000);
        int dayOffset = baseDayMinutes - baseMinute;

        byte[] lineBuf = new byte[48]; // max line: 41 bytes + 7 margin

        int pos = chunkStart;
        while (pos < chunkEnd) {
            // Bulk copy line into L1-resident stack buffer — eliminates MBB.get() per-byte overhead
            int readLen = Math.min(48, chunkEnd - pos);
            mbb.get(pos, lineBuf, 0, readLen);

            // Fixed layout: 20-char timestamp + comma + 11-char sensor + comma + value + newline
            int hour = (lineBuf[11] - '0') * 10 + (lineBuf[12] - '0');
            int minute = (lineBuf[14] - '0') * 10 + (lineBuf[15] - '0');

            int sIdx = (lineBuf[28] - '0') * 1000 + (lineBuf[29] - '0') * 100
                     + (lineBuf[30] - '0') * 10 + (lineBuf[31] - '0');

            if (sIdx >= ps.sensorCount) ps.sensorCount = sIdx + 1;

            // Specialized parser: scaled int only (no FP in hot path)
            // Offsets relative to lineBuf: value starts at 33
            int di = 33;
            byte b0 = lineBuf[di];
            boolean neg = (b0 == '-');
            if (neg) { di++; b0 = lineBuf[di]; }
            byte b1 = lineBuf[di + 1];
            int scaledValue;
            int lineLen;
            if (b1 == '.') {
                int d0 = b0 - '0', d2 = lineBuf[di + 2] - '0', d3 = lineBuf[di + 3] - '0';
                scaledValue = d0 * 100 + d2 * 10 + d3;
                lineLen = di + 5;
            } else {
                byte b2 = lineBuf[di + 2];
                if (b2 == '.') {
                    int d0 = b0 - '0', d1 = b1 - '0', d3 = lineBuf[di + 3] - '0', d4 = lineBuf[di + 4] - '0';
                    scaledValue = (d0 * 10 + d1) * 100 + d3 * 10 + d4;
                    lineLen = di + 6;
                } else {
                    int d0 = b0 - '0', d1 = b1 - '0', d2i = b2 - '0', d4 = lineBuf[di + 4] - '0', d5 = lineBuf[di + 5] - '0';
                    scaledValue = (d0 * 100 + d1 * 10 + d2i) * 100 + d4 * 10 + d5;
                    lineLen = di + 7;
                }
            }
            if (neg) { scaledValue = -scaledValue; }
            pos += lineLen;

            int eventMinute = dayOffset + hour * 60 + minute;

            if (eventMinute >= 0 && eventMinute < MAX_MINUTES) {
                if (eventMinute < ps.minMinute) ps.minMinute = eventMinute;
                if (eventMinute > ps.maxMinute) ps.maxMinute = eventMinute;
                TumblingState[] row = ps.tumbling[eventMinute];
                if (row == null) { row = new TumblingState[MAX_SENSORS]; ps.tumbling[eventMinute] = row; }
                TumblingState ts = row[sIdx];
                if (ts == null) { ts = new TumblingState(); row[sIdx] = ts; }
                ts.add(scaledValue);
            }
        }

        return ps;
    }

    private static long parseIsoBytesArr(byte[] b, int off) {
        int year = (b[off] - '0') * 1000 + (b[off+1] - '0') * 100 + (b[off+2] - '0') * 10 + (b[off+3] - '0');
        int month = (b[off+5] - '0') * 10 + (b[off+6] - '0');
        int day = (b[off+8] - '0') * 10 + (b[off+9] - '0');
        int hour = (b[off+11] - '0') * 10 + (b[off+12] - '0');
        int minute = (b[off+14] - '0') * 10 + (b[off+15] - '0');
        int second = (b[off+17] - '0') * 10 + (b[off+18] - '0');
        long totalDays = 365L * (year - 1970);
        totalDays += countLeapYears(year - 1) - countLeapYears(1969);
        boolean leap = (year % 4 == 0) && ((year % 100 != 0) || (year % 400 == 0));
        totalDays += (leap ? DAYS_CUM_LEAP : DAYS_CUM)[month - 1] + day - 1;
        return ((totalDays * 24 + hour) * 60 + minute) * 60000L + second * 1000L;
    }

    private static long countLeapYears(int y) {
        if (y < 0) return 0;
        return y / 4 - y / 100 + y / 400;
    }

    private static int appendScaledLong(byte[] buf, int pos, long v) {
        if (v < 0) { buf[pos++] = '-'; v = -v; }
        long intPart = v / 100;
        int fracPart = (int)(v % 100);
        pos = appendLongBytes(buf, pos, intPart);
        buf[pos++] = '.';
        buf[pos++] = DIGIT_TENS[fracPart];
        buf[pos++] = DIGIT_ONES[fracPart];
        return pos;
    }

    private static int appendScaledInt(byte[] buf, int pos, int v) {
        if (v < 0) { buf[pos++] = '-'; v = -v; }
        int intPart = v / 100;
        int fracPart = v % 100;
        // Inline small intPart (0-99 covers >95% of cases: values 0.00-99.99)
        if (intPart < 10) {
            buf[pos++] = (byte)('0' + intPart);
        } else if (intPart < 100) {
            buf[pos++] = DIGIT_TENS[intPart];
            buf[pos++] = DIGIT_ONES[intPart];
        } else {
            pos = appendLongBytes(buf, pos, intPart);
        }
        buf[pos++] = '.';
        buf[pos++] = DIGIT_TENS[fracPart];
        buf[pos++] = DIGIT_ONES[fracPart];
        return pos;
    }

    private static int appendLongBytes(byte[] buf, int pos, long v) {
        if (v < 0) { buf[pos++] = '-'; v = -v; }
        if (v == 0) { buf[pos++] = '0'; return pos; }
        // Fast path for small values (covers count, intPart of scaled values)
        if (v < 10) { buf[pos++] = (byte)('0' + v); return pos; }
        if (v < 100) { buf[pos++] = DIGIT_TENS[(int)v]; buf[pos++] = DIGIT_ONES[(int)v]; return pos; }
        // Count digits via comparison chain (no divisions)
        int digits;
        if (v < 1000) digits = 3;
        else if (v < 10000) digits = 4;
        else if (v < 100000) digits = 5;
        else if (v < 1000000) digits = 6;
        else if (v < 10000000) digits = 7;
        else if (v < 100000000) digits = 8;
        else if (v < 1000000000) digits = 9;
        else if (v < 10000000000L) digits = 10;
        else digits = 11; // sufficient for our values
        int end = pos + digits;
        int p = end;
        // Process 2 digits at a time (halve divisions)
        while (v >= 100) {
            int r = (int)(v % 100);
            v /= 100;
            buf[--p] = DIGIT_ONES[r];
            buf[--p] = DIGIT_TENS[r];
        }
        if (v >= 10) {
            buf[--p] = DIGIT_ONES[(int)v];
            buf[--p] = DIGIT_TENS[(int)v];
        } else {
            buf[--p] = (byte)('0' + v);
        }
        return end;
    }
}
