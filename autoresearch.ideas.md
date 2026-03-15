# Deferred Ideas

## High Priority (processChunkMBB 16.7%, quickselect 16.9%, sliding emit 16.3%)
- Batch MBB reads: read 64-128 bytes per line into local byte[] via mbb.get(pos, buf, 0, len), parse from local buffer — reduces ~20 MBB.get(int) calls to 1 bulk call per line
- Skip sensor name String allocation for already-seen sensors — use boolean[] seen array
- Pipeline: overlap output write with sliding emit (start writing tumbling while sliding still running)
- Pre-allocate TumblingState pool to reduce GC (4.2% profile)
- Introselect: hybrid quickselect+median-of-medians for guaranteed O(n) worst case
- Incremental sliding window: when advancing m→m+1, remove minute m values and add minute m+5 (avoid full copy+quickselect)

## Medium Priority
- Float instead of double for values[] — halve memory, better cache utilization (verify precision)
- Eliminate `new TumblingState[MAX_SENSORS]` per-minute allocation in processChunk — lazy sparse structure
- GraalVM native-image — AOT compilation eliminates JIT warmup, potentially faster steady state
- Reduce output size: smaller number formatting (fewer decimal places if checks allow)
- Use long-based encoding for sensor names instead of String (avoid String allocation)

## Lower Priority
- NUMA-aware chunk assignment
- Custom percentile algorithm: selection networks for small N
- Vector API (Java 21+ preview) for batch numeric operations
- Reduce merge contention with lock-free CAS on TumblingState slots

## Tried and Failed (do not retry)
- Arrays.sort replacing quickselect (-5%)
- Insertion sort for small N (-3%)
- ParallelGC (-15%), AlwaysPreTouch (no effect)
- MemorySegment API (bimodal JIT, 6.2-10.2M)
- Byte[] copy from mmap (-10% vs zero-copy)
- pread-based parallel reading (-12%)
- SWAR newline scan on MBB (negligible, ~7 bytes to scan after skip-31)
- Fused sliding+tumbling emit (-13%)
- Minute-range merge parallelism (-10%)
- Pre-sort values during merge (-25%)
- Fused merge+sliding (-19%)
