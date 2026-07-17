# Helix — NASDAQ ITCH 5.0 Feed Handler & Order-Book Engine

Zero-allocation Java engine that decodes NASDAQ TotalView-ITCH 5.0 and rebuilds
full price-time-priority order books from a memory-mapped capture. Built for
throughput and steady-state allocation-freedom on the hot path.

## What it does

- **Flyweight ITCH decoder** (`itch/ItchParser`) reads messages in place out of a
  `DirectBuffer` and dispatches primitive fields to a listener — no per-message
  objects. Stock symbols are packed into a `long` rather than interned as strings.
- **Price-time-priority order books** (`book/`) with pooled, recycled orders and
  price levels held in intrusive FIFO queues, so add/cancel/execute/delete/replace
  are O(1) pointer swaps against Agrona primitive-keyed maps.
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
| Decode + full order-book rebuild | **~5.0M messages/sec** (1.0 ops/s × 5M) |
| Book op, add+delete pair (SampleTime) | p50 **83 ns**, p99 **208 ns**, avg 70 ns |

At the end-to-end rate a full ITCH trading day (~230M messages) rebuilds in
roughly 45 seconds.

## Correctness

`OrderBookTest` pins price-time-priority semantics — FIFO time priority within a
level, partial vs. full execution, best-bid/ask promotion on level collapse, and
replace losing time priority — and is the reference the replay path is checked
against. `ParserRoundTripTest` proves the wire format is symmetric: every field
`ItchWriter` emits decodes back identically through `ItchParser`.

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
book/    OrderBook, PriceLevel, Order, Pool, BookManager
io/      ItchFileReader, ItchWriter, SyntheticCapture
engine/  Replay                          — end-to-end driver
```

## Roadmap

- Array-backed price ladder to replace the best-level rescan on level collapse
  (the one known non-constant step under heavy top-of-book churn).
- Java Flight Recorder allocation profiling wired into CI to assert zero
  steady-state allocation on the hot path.
- Real ITCH capture ingestion path alongside the synthetic generator.
