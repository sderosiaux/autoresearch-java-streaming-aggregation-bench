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
### Phase breakdown at 5M ev/s (current best, exp 49):
- Parse (parallel chunk processing): ~777ms (46%)
- Merge+pct (parallel merge+percentile): ~322ms (19%)
- Emit+convert (parallel StringBuilder→byte[]): ~100ms (6%)
- Output (stdout write, ~160MB): ~500ms (30%)

### Key: parse and output dominate. Both are near their theoretical limits for this architecture. Output is I/O-bound (pipe). Parse is bounded by per-event processing overhead (5 sliding window assignments per event).

## What's Been Tried (55 experiments, 18 kept)

| # | Experiment | Metric | Result |
|---|-----------|--------|--------|
| 0 | Baseline | 57,823 | baseline |
| 1-11 | Parsing/data structure/emit opts | → 1,857,700 | series of keeps |
| 13 | Multi-threaded parallel chunks + byte[] | 2,683,123 | KEEP (+44%) |
| 19 | Parallel merge+emit by sensor range | 3,480,682 | KEEP (+30%) |
| 32 | Sorted-order emit (no sort) | 3,954,132 | KEEP (+13.6%) |
| 34 | Parallel emit by minute range | 4,327,131 | KEEP (+9.4%) |
| 35 | Optimized sliding window loop | 4,504,504 | KEEP (+4.1%) |
| 39 | Parallel byte[] conversion in emit | 4,928,536 | KEEP (+9.4%) |
| 49 | Bundled emit: prefixes + direct append | 5,010,020 | KEEP (+1.7%) |

## Tabu
- Single-partition: sequential parsing too slow (-38%)
- Pre-sorted sliding: sort overhead > quickselect savings (-13%)
- byte[] output buffer: alloc overhead (-10%)
- Fused merge+emit: poor cross-partition cache locality (-5%)
- 8MB BufferedOutputStream: cache pressure (-5%)
- Fewer chunks (nThreads/2): reduced parse parallelism
- Fixed-offset comma: JIT already optimizes
- Cached date computation: JIT already optimizes
- Skip-ahead newline scan: JIT already optimizes
- FileChannel output: no gain over BufferedOutputStream
- Pre-computed timestamp prefixes alone: within noise
- Direct append (no sb2) alone: within noise
- Bounded minute range: only ~60 empty slots, negligible

## Landscape Model
Current: 5.01M ev/s (87x baseline). Architecture: parallel parse → parallel merge+pct → parallel emit+byte[]convert → sequential output.

**Exhausted dimensions**: parsing micro-opts (JIT handles), emit strategy, sort elimination, merge parallelism, output method, buffer sizes, chunk count.

**Remaining opportunity**: SWAR/Unsafe byte processing, MemorySegment API, overlapping parse with output via pipelining, reducing SlidingState memory pressure, data structure transposition [sensor][minute] → [minute][sensor].
