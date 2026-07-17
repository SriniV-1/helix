package dev.srini.helix.engine;

import dev.srini.helix.book.BookManager;
import dev.srini.helix.book.Pool;
import dev.srini.helix.itch.ItchParser;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Multi-threaded replay that shards order books across worker threads by
 * stock-locate id.
 *
 * <p>Every ITCH message carries a stock-locate id in its header, and an
 * instrument's order lifecycle (add → execute/cancel/delete/replace) only ever
 * touches that instrument's book. So messages for a given instrument can be
 * routed to a single owning worker and applied in arrival order, while different
 * instruments proceed fully in parallel — no locks, no shared book state.
 *
 * <p>Pipeline: one reader thread walks the memory-mapped capture, reads the
 * 2-byte locate from each frame, and copies the raw message into the owning
 * shard's single-producer/single-consumer ring buffer (Agrona
 * {@link OneToOneRingBuffer}). Each worker drains its ring, decodes with its own
 * parser, and rebuilds its books. Decode work is thereby parallelized too; the
 * reader only routes.
 *
 * <p>Usage: {@code ShardedReplay <capture.itch> [maxShards]}
 */
public final class ShardedReplay {

    private static final int RING_CAPACITY = 1 << 22;   // 4 MiB payload per shard
    private static final ByteOrder BE = ByteOrder.BIG_ENDIAN;
    private static final int WARMUP = 3;
    private static final int MEASURE = 5;

    public static void main(String[] args) throws Exception {
        final Path capture = Path.of(args.length > 0 ? args[0] : "captures/synthetic.itch");
        final int maxShards = args.length > 1 ? Integer.parseInt(args[1]) : 8;

        final byte[] bytes = Files.readAllBytes(capture);
        final UnsafeBuffer src = new UnsafeBuffer(bytes);
        final long size = bytes.length;
        final int totalMessages = countFrames(src, size);

        System.out.printf("capture   : %s (%,d bytes, %,d messages)%n", capture, size, totalMessages);
        System.out.printf("cores     : %d%n%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("%-10s %14s %14s%n", "config", "msgs/sec", "vs 1-thread");

        final double single = bestRate(() -> singleThread(src, size), totalMessages);
        System.out.printf("%-10s %,14.0f %14s%n", "1 thread", single, "1.00x");

        for (int shards = 2; shards <= maxShards; shards <<= 1) {
            final int n = shards;
            final double rate = bestRate(() -> sharded(src, size, n, totalMessages), totalMessages);
            System.out.printf("%-10s %,14.0f %13.2fx%n", shards + " shards", rate, rate / single);
        }
    }

    // ------------------------------------------------------------------

    private interface Run {
        long elapsedNanos() throws Exception;
    }

    /** Warm up, then return the best (max) message rate over several measured runs. */
    private static double bestRate(Run run, int messages) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            run.elapsedNanos();
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < MEASURE; i++) {
            best = Math.min(best, run.elapsedNanos());
        }
        return messages / (best / 1e9);
    }

    /** Baseline: decode and apply inline on the calling thread. */
    private static long singleThread(UnsafeBuffer src, long size) {
        final BookManager books = new BookManager(new Pool(1 << 20, 1 << 16), 4096);
        final ItchParser parser = new ItchParser(books);
        final long start = System.nanoTime();
        int offset = 0;
        while (offset + 2 <= size) {
            final int len = src.getShort(offset, BE) & 0xFFFF;
            if (len == 0 || offset + 2 + len > size) {
                break;
            }
            parser.parse(src, offset + 2);
            offset += 2 + len;
        }
        return System.nanoTime() - start;
    }

    /** Sharded: route by locate to per-worker rings; workers decode + apply in parallel. */
    private static long sharded(UnsafeBuffer src, long size, int shards, int expected)
            throws InterruptedException {
        final OneToOneRingBuffer[] rings = new OneToOneRingBuffer[shards];
        final Worker[] workers = new Worker[shards];
        final Thread[] threads = new Thread[shards];
        for (int i = 0; i < shards; i++) {
            final UnsafeBuffer rb = new UnsafeBuffer(
                    ByteBuffer.allocateDirect(RING_CAPACITY + RingBufferDescriptor.TRAILER_LENGTH));
            rings[i] = new OneToOneRingBuffer(rb);
            workers[i] = new Worker(rings[i]);
            threads[i] = new Thread(workers[i], "shard-" + i);
            threads[i].start();
        }

        final long start = System.nanoTime();
        int offset = 0;
        while (offset + 2 <= size) {
            final int len = src.getShort(offset, BE) & 0xFFFF;
            if (len == 0 || offset + 2 + len > size) {
                break;
            }
            final int locate = src.getShort(offset + 3, BE) & 0xFFFF; // header: type(1) then locate(2)
            final OneToOneRingBuffer ring = rings[locate & (shards - 1)];
            final int msgOffset = offset + 2;
            while (!ring.write(1, src, msgOffset, len)) {
                Thread.onSpinWait();
            }
            offset += 2 + len;
        }
        for (Worker w : workers) {
            w.done = true;
        }
        for (Thread t : threads) {
            t.join();
        }
        final long elapsed = System.nanoTime() - start;

        long processed = 0;
        for (Worker w : workers) {
            processed += w.books.messages();
        }
        if (processed != expected) {
            throw new IllegalStateException("sharded processed " + processed + " != " + expected);
        }
        return elapsed;
    }

    private static final class Worker implements Runnable {
        final OneToOneRingBuffer ring;
        final BookManager books = new BookManager(new Pool(1 << 18, 1 << 14), 4096);
        private final ItchParser parser = new ItchParser(books);
        private final MessageHandler handler = (typeId, buffer, index, length) -> parser.parse(buffer, index);
        volatile boolean done;

        Worker(OneToOneRingBuffer ring) {
            this.ring = ring;
        }

        @Override
        public void run() {
            while (!done) {
                if (ring.read(handler) == 0) {
                    Thread.onSpinWait();
                }
            }
            // The reader writes every message before setting done, so once done is
            // observed no more writes occur: drain whatever remains to zero.
            while (ring.read(handler) != 0) {
                // keep draining
            }
        }
    }

    private static int countFrames(UnsafeBuffer src, long size) {
        int count = 0;
        int offset = 0;
        while (offset + 2 <= size) {
            final int len = src.getShort(offset, BE) & 0xFFFF;
            if (len == 0 || offset + 2 + len > size) {
                break;
            }
            offset += 2 + len;
            count++;
        }
        return count;
    }

    private ShardedReplay() {
    }
}
