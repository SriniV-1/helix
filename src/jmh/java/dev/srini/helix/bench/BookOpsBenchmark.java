package dev.srini.helix.bench;

import dev.srini.helix.book.OrderBook;
import dev.srini.helix.book.Pool;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Median latency of a single book operation in steady state. Each invocation
 * does one add and one matched remove (delete) against a warmed book already
 * holding a realistic resting depth, so pool and level maps are hot.
 *
 * <p>Reported as average and p50 nanoseconds per (add+delete) pair via
 * SampleTime; the resume-relevant figure is per single operation, i.e. half the
 * reported pair latency.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class BookOpsBenchmark {

    private OrderBook book;
    private long ref;
    private int price;

    @Setup(Level.Trial)
    public void setUp() {
        book = new OrderBook(new Pool(1 << 20, 1 << 16), 1 << 20);
        // Pre-load resting depth across a band of price levels.
        for (int i = 0; i < 200_000; i++) {
            final byte side = (i & 1) == 0 ? OrderBook.BUY : OrderBook.SELL;
            book.add(ref++, side, 100, 100_0000 + (i % 500) * 100);
        }
        price = 100_0000;
    }

    @Benchmark
    public long addThenDelete() {
        final long r = ref++;
        book.add(r, OrderBook.BUY, 100, price + ((int) (r % 500)) * 100);
        book.delete(r);
        return r;
    }
}
