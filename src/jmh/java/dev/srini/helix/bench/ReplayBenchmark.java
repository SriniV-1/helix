package dev.srini.helix.bench;

import dev.srini.helix.book.BookManager;
import dev.srini.helix.book.Pool;
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
 * Steady-state throughput of the decode + book-rebuild hot path.
 *
 * <p>A synthetic capture is generated once into an off-heap buffer; each
 * invocation replays the entire buffer through a fresh {@link BookManager} so
 * the measured work is decode + book mutation with no I/O. Reported as
 * messages/second after warmup, which is the number that reflects JIT-compiled
 * steady state rather than a cold first run.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class ReplayBenchmark {

    private UnsafeBuffer buffer;
    private long fileBytes;
    private int messageCount;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        final Path tmp = Files.createTempFile("helix-bench", ".itch");
        try {
            final int messages = 5_000_000;
            SyntheticCapture.generate(tmp, 500, messages, 1234L);
            final byte[] bytes = Files.readAllBytes(tmp);
            fileBytes = bytes.length;
            buffer = new UnsafeBuffer(bytes);
            messageCount = countMessages();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private int countMessages() {
        int count = 0;
        int offset = 0;
        while (offset + 2 <= fileBytes) {
            final int len = buffer.getShort(offset, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            if (len == 0 || offset + 2 + len > fileBytes) {
                break;
            }
            offset += 2 + len;
            count++;
        }
        return count;
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void replayFullDay(Blackhole bh) {
        final Pool pool = new Pool(1 << 20, 1 << 16);
        final BookManager books = new BookManager(pool, 4096);
        final ItchParser parser = new ItchParser(books);

        int offset = 0;
        while (offset + 2 <= fileBytes) {
            final int len = buffer.getShort(offset, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            if (len == 0 || offset + 2 + len > fileBytes) {
                break;
            }
            parser.parse(buffer, offset + 2);
            offset += 2 + len;
        }
        bh.consume(books.messages());
    }

    public int messageCount() {
        return messageCount;
    }
}
