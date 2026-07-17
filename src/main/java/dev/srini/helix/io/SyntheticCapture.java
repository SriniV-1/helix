package dev.srini.helix.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

/**
 * Generates a synthetic but structurally valid ITCH 5.0 capture so the engine is
 * runnable and benchmarkable without NASDAQ's licensed historical data.
 *
 * <p>The generated stream is not a market simulation; it is a workload with the
 * message-type mix and per-symbol order lifecycle (add, partial execute, cancel,
 * delete, replace) that exercises every code path in the book. Order references
 * are unique and monotonically increasing; every execute/cancel/delete targets a
 * live reference, so a correct engine ends the run with a consistent book.
 *
 * <p>Usage: {@code SyntheticCapture <out.itch> <symbols> <messages>}
 */
public final class SyntheticCapture {

    public static void main(String[] args) throws IOException {
        final Path out = Path.of(args.length > 0 ? args[0] : "captures/synthetic.itch");
        final int symbols = args.length > 1 ? Integer.parseInt(args[1]) : 500;
        final long target = args.length > 2 ? Long.parseLong(args[2]) : 5_000_000L;

        java.nio.file.Files.createDirectories(out.toAbsolutePath().getParent());
        final long written = generate(out, symbols, target, 42L);
        System.out.printf("wrote %,d messages (%,d bytes) to %s%n", written, sizeOf(out), out);
    }

    private static long sizeOf(Path p) throws IOException {
        return java.nio.file.Files.size(p);
    }

    /**
     * @return number of messages written
     */
    public static long generate(Path out, int symbols, long targetMessages, long seed) throws IOException {
        final Random rng = new Random(seed);
        long messages = 0;
        long nextRef = 1;
        long ts = 9L * 3600 * 1_000_000_000L; // 09:00 in nanos since midnight

        // Live order refs per symbol, kept in a simple growable ring for reuse.
        final long[][] live = new long[symbols][64];
        final int[] liveCount = new int[symbols];
        final int[] basePrice = new int[symbols];
        for (int s = 0; s < symbols; s++) {
            basePrice[s] = 100_0000 + rng.nextInt(400_0000); // $100..$500 in E4
        }

        try (ItchWriter w = new ItchWriter(out)) {
            w.systemEvent(ts, (byte) 'O'); // start of messages
            messages++;

            while (messages < targetMessages) {
                final int s = rng.nextInt(symbols);
                final int locate = s + 1;
                ts += 1 + rng.nextInt(500);

                final int roll = rng.nextInt(100);
                if (roll < 55 || liveCount[s] == 0) {
                    // Add order
                    final byte side = rng.nextBoolean() ? (byte) 'B' : (byte) 'S';
                    final int drift = rng.nextInt(200) - 100;
                    final int price = Math.max(1_0000, basePrice[s] + drift * 100);
                    final int shares = (1 + rng.nextInt(20)) * 100;
                    final long ref = nextRef++;
                    w.addOrder(locate, ts, ref, side, shares, symbolBits(s), price);
                    if (liveCount[s] < live[s].length) {
                        live[s][liveCount[s]++] = ref;
                    }
                } else if (roll < 70) {
                    final int idx = rng.nextInt(liveCount[s]);
                    w.orderExecuted(locate, ts, live[s][idx], 100, nextRef++);
                } else if (roll < 82) {
                    final int idx = rng.nextInt(liveCount[s]);
                    w.orderCancel(locate, ts, live[s][idx], 100);
                } else if (roll < 94) {
                    final int idx = rng.nextInt(liveCount[s]);
                    w.orderDelete(locate, ts, live[s][idx]);
                    live[s][idx] = live[s][--liveCount[s]];
                } else {
                    final int idx = rng.nextInt(liveCount[s]);
                    final long oldRef = live[s][idx];
                    final long newRef = nextRef++;
                    final int price = Math.max(1_0000, basePrice[s] + (rng.nextInt(200) - 100) * 100);
                    w.orderReplace(locate, ts, oldRef, newRef, (1 + rng.nextInt(20)) * 100, price);
                    live[s][idx] = newRef;
                }
                messages++;
            }
        }
        return messages;
    }

    /** Pack a short synthetic symbol like "SYM00042" into 8 big-endian bytes. */
    static long symbolBits(int index) {
        final byte[] b = new byte[8];
        b[0] = 'S';
        b[1] = 'Y';
        b[2] = 'M';
        for (int i = 7; i >= 3; i--) {
            b[i] = (byte) ('0' + (index % 10));
            index /= 10;
        }
        long v = 0;
        for (byte value : b) {
            v = (v << 8) | (value & 0xFF);
        }
        return v;
    }

    private SyntheticCapture() {
    }
}
