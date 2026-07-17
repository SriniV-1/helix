# Helix — NASDAQ ITCH 5.0 Feed Handler & Matching Engine

Zero-allocation Java engine that decodes NASDAQ TotalView-ITCH 5.0, rebuilds full
price-time-priority order books from a memory-mapped capture, and matches inbound
aggressive orders against the resting book. Built for throughput and steady-state
allocation-freedom on the hot path.

**Live demo:** https://sriniv-1.github.io/helix/ — the matching engine running in
your browser (a faithful JS port of the crossing logic) with a live order book,
time & sales tape, and interactive order entry.

## What it does

- **Flyweight ITCH decoder** (`itch/ItchParser`) reads messages in place out of a
  `DirectBuffer` and dispatches primitive fields to a listener — no per-message
  objects. Stock symbols are packed into a `long` rather than interned as strings.
- **Price-time-priority order books** (`book/`) with pooled, recycled orders and
  price levels held in intrusive FIFO queues, so add/cancel/execute/delete/replace
  are O(1) pointer swaps against Agrona primitive-keyed maps.
- **Continuous matching engine** (`book/MatchingEngine`) crosses inbound aggressive
  orders against the resting book in strict price-time priority — lowest ask / highest
  bid first, oldest order first within a level — with partial fills, multi-level
  sweeps, resting residual for limits, and market orders that take available liquidity.
- **Memory-mapped replay** (`io/ItchFileReader`) maps a length-framed capture once
  and drives the decoder straight over the mapping.
- **Synthetic capture generator** (`io/SyntheticCapture`) produces a structurally
  valid ITCH stream so the engine runs and benchmarks without NASDAQ's licensed
  historical data.

## Measured performance

JMH, warm JVM, single core (Apple Silicon, JDK 24). Reproduce with `./gradlew jmh`.

| Path | Result |
| --- | --- |
| ITCH decode only (to counting sink) | **~61M messages/sec** (12.3 ops/s × 5M) |
| Decode + full order-book rebuild, 1 thread | **~5.0M messages/sec** (1.0 ops/s × 5M) |
| Decode + rebuild, 4-shard sharded replay | **~9.6M messages/sec** (2.2× single-thread) |
| Book op, add+delete pair (SampleTime) | p50 **83 ns**, p99 **208 ns**, avg 70 ns |

The sharded pipeline (`engine/ShardedReplay`) partitions books across worker
threads by stock-locate id; on an 8-core machine 4 shards is the sweet spot (8
shards oversubscribe once the reader thread is counted). At the sharded rate a
full ITCH trading day (~230M messages) rebuilds in roughly 24 seconds.

## Allocation

`AllocationProfile` (in `engine/`) proves the steady-state hot path allocates
nothing, two independent ways: exact HotSpot per-thread accounting
(`ThreadMXBean.getThreadAllocatedBytes`) reports **0 bytes/message** on decode and
**0 bytes/op** on book churn after warmup, cross-checked by a **Java Flight Recorder**
`ObjectAllocationSample` recording that finds zero samples attributed to engine
code. It writes `captures/alloc.jfr` for inspection.

```bash
./gradlew -q compileJava
CP="build/classes/java/main:$(find ~/.gradle -name 'agrona-*.jar' | head -1)"
java -cp "$CP" dev.srini.helix.engine.AllocationProfile
```

## Correctness

`OrderBookTest` pins price-time-priority semantics — FIFO time priority within a
level, partial vs. full execution, best-bid/ask promotion on level collapse, and
replace losing time priority — and is the reference the replay path is checked
against. `MatchingEngineTest` pins the crossing rules: price then time priority,
partial fills, multi-level sweeps, resting residual, market-order behavior, and
trades printing at the passive price. `ParserRoundTripTest` proves the wire format
is symmetric: every field `ItchWriter` emits decodes back identically through
`ItchParser`.

## Build & run

```bash
./gradlew test                 # correctness
./gradlew jmh                  # benchmarks

# generate a capture and replay it end to end
./gradlew -q compileJava
CP="build/classes/java/main:$(find ~/.gradle -name 'agrona-*.jar' | head -1)"
java -cp "$CP" dev.srini.helix.io.SyntheticCapture captures/day.itch 500 5000000
java -cp "$CP" dev.srini.helix.engine.Replay captures/day.itch
```

## Layout

```
itch/    ItchParser, ItchListener        — zero-alloc ITCH 5.0 decoder
book/    OrderBook, PriceLevel, Order, Pool, BookManager, MatchingEngine
         PriceLadder                     — evaluated array-backed level store
io/      ItchFileReader, ItchWriter, SyntheticCapture
engine/  Replay, ShardedReplay,          — single- and multi-threaded drivers
         AllocationProfile               — JFR / exact allocation check
docs/    index.html                      — live in-browser demo (GitHub Pages)
```

## Engineering notes

**Array-backed price ladder — evaluated, not adopted.** `PriceLadder` stores
levels in a windowed contiguous array indexed by tick offset, so level lookup is
one array read and next-best on collapse is a short cache-friendly walk, with a
hash-map fallback for out-of-window/unaligned prices. Benchmarked head to head
against the hash-map book: it **halved single-book add/cancel p50 (83 ns → 42 ns)**
but did **not** win the full 500-book replay — one sparse array per side per book
adds enough cache pressure that end-to-end throughput came in at/below the hash-map
book (~4.5M vs ~5.0M msgs/sec), and no single window size won both the deep-single-book
and many-shallow-book cases. The hash-map book stays in production; `PriceLadder`
and its tests are kept to document the tradeoff rather than hide it.

## Roadmap

- Adaptive per-book store: hash-map by default, promote a hot instrument to a
  ladder once its depth justifies the array.
- Real ITCH capture ingestion path alongside the synthetic generator.
- Pin worker threads to cores and split the reader across NUMA nodes.
