# Autoresearch: Streaming Aggregation Throughput

## Objective
Maximize throughput (events/sec) of `StreamingAggregator.java` — a single-threaded Java streaming window aggregation engine. Processes 10M timestamped CSV sensor events through tumbling windows (1min: count/sum/min/max/avg per sensor) and sliding windows (5min/1min slide: p50/p99 per sensor). 5% late data with watermark-based emission.

## Metrics
- **Primary**: throughput (events/sec, higher is better)
- **Secondary**: elapsed_ms

## How to Run
`./autoresearch.sh` outputs `METRIC throughput=<n>` lines.
Log results with `/home/claude/.claude/plugins/cache/sderosiaux-claude-plugins/claudecode-autoresearch/0.1.0/scripts/log-experiment.sh`.

## Files in Scope
- `src/StreamingAggregator.java` — the aggregation engine (only file to modify)
- NOTE: files are gitignored, must use `git add -f` to commit

## Off Limits
- `src/DataGenerator.java` — generates test data
- `src/BatchValidator.java` — correctness oracle
- `autoresearch.sh` — benchmark harness
- `autoresearch.checks.sh` — correctness checks

## Constraints
- `./autoresearch.checks.sh` must pass (output matches BatchValidator)
- Java 21 (can use modern APIs: records, pattern matching, VarHandles, etc.)
- No external dependencies (stdlib only)
- Output format must match exactly (checked by diff vs BatchValidator)

## Profiling Notes
### Baseline profile (57,823 ev/s)
- 30% sorting (SlidingState.percentile sorts on every call)
- 10% String.format for output
- 9% Double.compareTo (autoboxing in sort of List<Double>)
- 6% regex in String.split(",")
- 3% HashMap key operations

### Post-optimization profile (328,558 ev/s)
- emitReadyWindows scanning empty array slots = #1 hotspot
- Quickselect + emission still significant
- StringBuilder + I/O moderate

### Key insight (experiment 9)
Emission frequency dominates: 10K -> 100K interval = 3.2x speedup. The scan of 1000 sensors * minute range on every emission was the bottleneck.

## What's Been Tried

| # | Experiment | Metric | Result |
|---|-----------|--------|--------|
| 0 | Baseline (Instant.parse, String.split, ArrayList<Double>, String.format) | 57,823 | baseline |
| 1 | indexOf parsing, manual ISO timestamp, StringBuilder, double[] sliding | 199,282 | KEEP (+244%) |
| 2 | Single sort for both p50/p99 | 227,588 | KEEP (+14%) |
| 3 | 1MB reader buffer + sensor ID interning | 236,966 | KEEP (+4%) |
| 4 | Quickselect O(n) for percentiles | 244,953 | KEEP (+3%) |
| 5 | Fast manual double parser (alone) | 240,853 | DISCARD (within noise) |
| 6 | Compact SensorWindow key + fast double parser + separate emitted sets | 270,233 | KEEP (+10%) |
| 7 | Array-indexed windows replacing HashMaps | 328,558 | KEEP (+22%) |
| 8 | Bounded scan with floor/ceiling in emitReadyWindows | 357,935 | KEEP (+9%) |
| 9 | Emit every 100K instead of 10K | 1,155,401 | KEEP (+223%) |

### Dead ends
- Fast double parser alone: no measurable impact over Double.parseDouble

### Tabu
- Don't try: changing emission to 10K or 50K (proven inferior)

## Landscape Model
Current: 1.15M ev/s (20x baseline). Major wins came from:
1. Parsing/output formatting (3.4x from baseline)
2. Data structure changes: arrays vs HashMaps (+22%)
3. Emission frequency reduction (+223%)

Gradient points toward: reducing per-event overhead (allocation, map lookups). Remaining cost is in the hot loop itself (parse, assign to windows). Multi-threading is the obvious next frontier but adds complexity.

Promising unexplored: byte-level parsing (avoid String), memory-mapped I/O, JVM flags, further emission reduction.
Exhausted: parsing optimizations (diminishing returns), HashMap vs arrays (done).
