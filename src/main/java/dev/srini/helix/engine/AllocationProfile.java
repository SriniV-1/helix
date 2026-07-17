package dev.srini.helix.engine;

import com.sun.management.ThreadMXBean;
import dev.srini.helix.book.OrderBook;
import dev.srini.helix.book.Pool;
import dev.srini.helix.itch.ItchListener;
import dev.srini.helix.itch.ItchParser;
import dev.srini.helix.io.SyntheticCapture;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Verifies that the steady-state hot path allocates nothing, two independent ways:
 *
 * <ol>
 *   <li><b>Exact:</b> HotSpot per-thread allocation accounting
 *       ({@code ThreadMXBean.getThreadAllocatedBytes}) measured across the warm
 *       decode loop and the warm book-churn loop — this is not sampled, so a
 *       result of ~0 bytes/op is a hard proof.</li>
 *   <li><b>Java Flight Recorder:</b> an {@code ObjectAllocationSample} recording
 *       is captured over the same window and parsed back in-process; any sample
 *       whose stack touches {@code dev.srini.helix} hot-path code is reported.</li>
 * </ol>
 *
 * <p>Run after building: writes {@code captures/alloc.jfr} for inspection.
 */
public final class AllocationProfile {

    private static final String PKG = "dev.srini.helix";

    public static void main(String[] args) throws IOException {
        final Path tmp = Files.createTempFile("helix-alloc", ".itch");
        final byte[] bytes;
        try {
            SyntheticCapture.generate(tmp, 500, 5_000_000, 7L);
            bytes = Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
        final UnsafeBuffer buf = new UnsafeBuffer(bytes);
        final long size = bytes.length;

        final ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long tid = Thread.currentThread().threadId();

        // --- warm up JIT and the pools/books so measurement is steady state ---
        final CountingListener counter = new CountingListener();
        final ItchParser decodeParser = new ItchParser(counter);
        for (int i = 0; i < 20; i++) {
            decode(buf, size, decodeParser);
        }
        final ChurnBook churn = new ChurnBook();
        churn.warmup();

        // --- record JFR over the measured window ---
        final Recording rec = new Recording();
        rec.enable("jdk.ObjectAllocationSample").withStackTrace();
        rec.enable("jdk.ThreadAllocationStatistics").withPeriod(Duration.ofMillis(50));
        rec.start();

        // Phase 1: pure decode over the whole capture.
        final long a0 = tmx.getThreadAllocatedBytes(tid);
        final long messages = decode(buf, size, decodeParser);
        final long a1 = tmx.getThreadAllocatedBytes(tid);

        // Phase 2: steady book churn (add + delete against a warm book/pool).
        final long churnOps = 5_000_000L;
        final long b0 = tmx.getThreadAllocatedBytes(tid);
        churn.run(churnOps);
        final long b1 = tmx.getThreadAllocatedBytes(tid);

        rec.stop();
        final Path jfr = Path.of("captures/alloc.jfr");
        Files.createDirectories(jfr.toAbsolutePath().getParent());
        rec.dump(jfr);
        rec.close();

        final long decodeBytes = a1 - a0;
        final long churnBytes = b1 - b0;

        System.out.println("== Exact per-thread allocation (ThreadMXBean) ==");
        System.out.printf("decode : %,d messages, %,d bytes total, %.4f bytes/msg%n",
                messages, decodeBytes, decodeBytes / (double) messages);
        System.out.printf("churn  : %,d ops, %,d bytes total, %.4f bytes/op%n",
                churnOps, churnBytes, churnBytes / (double) churnOps);

        reportJfr(jfr);

        final boolean clean = decodeBytes < 64 * 1024 && churnBytes < 64 * 1024;
        System.out.println(clean
                ? "RESULT: steady-state hot path is allocation-free (both loops < 64 KiB total)."
                : "RESULT: allocation detected — see JFR for the offending call sites.");
    }

    private static void reportJfr(Path jfr) throws IOException {
        long helixWeight = 0, totalWeight = 0, helixSamples = 0, totalSamples = 0;
        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                final RecordedEvent e = rf.readEvent();
                if (!e.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                    continue;
                }
                final long weight = e.hasField("weight") ? e.getLong("weight") : 0;
                totalSamples++;
                totalWeight += weight;
                if (touchesHelix(e)) {
                    helixSamples++;
                    helixWeight += weight;
                }
            }
        }
        System.out.println("== Java Flight Recorder (ObjectAllocationSample) ==");
        System.out.printf("total allocation samples : %,d (~%,d bytes estimated)%n", totalSamples, totalWeight);
        System.out.printf("attributed to %s : %,d samples (~%,d bytes)%n", PKG, helixSamples, helixWeight);
        System.out.println("jfr written to " + jfr);
    }

    private static boolean touchesHelix(RecordedEvent e) {
        if (e.getStackTrace() == null) {
            return false;
        }
        for (RecordedFrame f : e.getStackTrace().getFrames()) {
            if (f.getMethod() != null && f.getMethod().getType() != null
                    && f.getMethod().getType().getName().startsWith(PKG)) {
                return true;
            }
        }
        return false;
    }

    private static long decode(UnsafeBuffer buf, long size, ItchParser parser) {
        long count = 0;
        int offset = 0;
        while (offset + 2 <= size) {
            final int len = buf.getShort(offset, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            if (len == 0 || offset + 2 + len > size) {
                break;
            }
            parser.parse(buf, offset + 2);
            offset += 2 + len;
            count++;
        }
        return count;
    }

    /** Warm book + pool exercised with a bounded add/delete cycle that nets to empty. */
    private static final class ChurnBook {
        private final OrderBook book = new OrderBook(new Pool(1 << 20, 1 << 16), 1 << 20);
        private long ref = 1;

        void warmup() {
            for (int i = 0; i < 200_000; i++) {
                book.add(ref++, i % 2 == 0 ? OrderBook.BUY : OrderBook.SELL, 100, 100_0000 + (i % 500) * 100);
            }
            run(1_000_000);
        }

        void run(long ops) {
            for (long i = 0; i < ops; i++) {
                final long r = ref++;
                book.add(r, OrderBook.BUY, 100, 100_0000 + (int) (r % 500) * 100);
                book.delete(r);
            }
        }
    }

    private static final class CountingListener implements ItchListener {
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

    private AllocationProfile() {
    }
}
