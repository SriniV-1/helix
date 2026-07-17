package dev.srini.helix.bench;

import dev.srini.helix.itch.ItchListener;
import dev.srini.helix.itch.ItchParser;
import dev.srini.helix.io.SyntheticCapture;
import org.agrona.concurrent.UnsafeBuffer;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Pure decode throughput: parse every message to a counting listener that does
 * no book work. Isolates wire-format decoding from book mutation so the two
 * costs can be reported separately.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class DecodeBenchmark {

    private UnsafeBuffer buffer;
    private long fileBytes;

    private static final class Counter implements ItchListener {
        long n;

        public void onSystemEvent(int a, long b, byte c) { n++; }
        public void onStockDirectory(int a, long b, long c) { n++; }
        public void onAddOrder(int a, long b, long c, byte d, int e, long f, int g) { n++; }
        public void onOrderExecuted(int a, long b, long c, int d, long e) { n++; }
        public void onOrderExecutedWithPrice(int a, long b, long c, int d, long e, byte f, int g) { n++; }
        public void onOrderCancel(int a, long b, long c, int d) { n++; }
        public void onOrderDelete(int a, long b, long c) { n++; }
        public void onOrderReplace(int a, long b, long c, long d, int e, int f) { n++; }
        public void onTrade(int a, long b, long c, byte d, int e, long f, int g, long h) { n++; }
        public void onOther(byte a, int b, long c) { n++; }
    }

    private final Counter counter = new Counter();

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        final Path tmp = Files.createTempFile("helix-decode", ".itch");
        try {
            SyntheticCapture.generate(tmp, 500, 5_000_000, 1234L);
            final byte[] bytes = Files.readAllBytes(tmp);
            fileBytes = bytes.length;
            buffer = new UnsafeBuffer(bytes);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Benchmark
    public void decodeFullDay(Blackhole bh) {
        final ItchParser parser = new ItchParser(counter);
        int offset = 0;
        while (offset + 2 <= fileBytes) {
            final int len = buffer.getShort(offset, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            if (len == 0 || offset + 2 + len > fileBytes) {
                break;
            }
            parser.parse(buffer, offset + 2);
            offset += 2 + len;
        }
        bh.consume(counter.n);
    }
}
