# Autoresearch: Streaming Aggregation Throughput

## Objective
Maximize throughput (events/sec) of `StreamingAggregator.java`. 10M timestamped CSV events -> tumbling + sliding windows. Java 21, no external deps.

## Metrics
- **Primary**: throughput (events/sec, higher is better)

## Files in Scope
- `src/StreamingAggregator.java` (must `git add -f` to commit)

## Off Limits
- DataGenerator.java, BatchValidator.java, autoresearch.sh, autoresearch.checks.sh

## Constraints
- `./autoresearch.checks.sh` must pass
- Java 21 stdlib only

## Profiling Notes
### Phase breakdown at 4.5M ev/s (current best, exp 35):
- Parse (parallel chunk processing): 777ms (46%)
- Merge+pct (parallel merge+percentile): 322ms (19%)
- Emit (parallel StringBuilder format): 5ms (0.3%)
- Output (stdout write, ~160MB): 584ms (35%)

### Key: parse and output dominate. Emit eliminated via sorted-order + parallel emit. Output is I/O-bound. Parse is the main CPU bottleneck.

## What's Been Tried (35 experiments)

| # | Experiment | Metric | Result |
|---|-----------|--------|--------|
| 0 | Baseline | 57,823 | baseline |
| 1-8 | Parsing/data structure optimizations | → 357,935 | series of keeps |
| 9-11 | Emit strategy + direct sensor index | → 1,857,700 | KEEP (+420%) |
| 13 | Multi-threaded parallel chunks + byte[] | 2,683,123 | KEEP (+44%) |
| 19 | Parallel merge+emit by sensor range | 3,480,682 | KEEP (+30%) |
| 24-29 | Various merge/sort/emit opts | — | all DISCARD |
| 30 | Single-partition (no merge) | 2,155,172 | DISCARD (-38%) |
| 31 | Pre-sorted sliding + merge-sort | 3,035,822 | DISCARD (-13%) |
| 32 | Sorted-order emit (no Collections.sort) | 3,954,132 | KEEP (+13.6%) |
| 33 | byte[] output buffer | 3,548,616 | DISCARD (-10%) |
| 34 | Parallel emit by minute range | 4,327,131 | KEEP (+9.4%) |
| 35 | Optimized sliding window loop | 4,504,504 | KEEP (+4.1%) |

## Tabu
- Single-partition: sequential parsing too slow
- Pre-sorted sliding states: sort overhead > quickselect savings
- byte[] output buffer: Long.toString().getBytes() alloc overhead
- Mmap byte-level: no gain over byte[] chunk
- Independent file handles: OS page cache already parallel
- Fixed-offset parsing: JIT already optimizes comma scan
- Pre-allocate sensor rows: memory waste
- parallelSort: overhead for small arrays
- BufferedWriter output: slower than StringBuilder

## Landscape Model
Current: 4.5M ev/s (78x baseline). Architecture: parallel parse → parallel merge+pct → parallel emit → sequential output.

**Gradient**: Parse=46%, Output=35%, Merge+pct=19%. Parse optimization or output pipelining are the highest-leverage areas.

**Promising unexplored**: MappedByteBuffer for parse (avoid copy), overlap parse with merge, reduce SlidingState.add allocations, FileChannel output instead of stdout pipe, reduce comma scanning overhead.

**Exhausted**: emit strategy, sort elimination, sliding loop structure, merge parallelism.
