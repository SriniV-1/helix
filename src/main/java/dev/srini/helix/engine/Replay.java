package dev.srini.helix.engine;

import dev.srini.helix.book.BookManager;
import dev.srini.helix.book.Pool;
import dev.srini.helix.io.ItchFileReader;
import dev.srini.helix.itch.ItchParser;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Replays an ITCH capture end to end: memory-map the file, decode every message,
 * rebuild all order books, and report throughput.
 *
 * <p>Usage: {@code Replay <capture.itch>}
 */
public final class Replay {

    public static void main(String[] args) throws IOException {
        final Path capture = Path.of(args.length > 0 ? args[0] : "captures/synthetic.itch");

        final Pool pool = new Pool(1 << 20, 1 << 16);
        final BookManager books = new BookManager(pool, 4096);
        final ItchParser parser = new ItchParser(books);

        try (ItchFileReader reader = new ItchFileReader(capture)) {
            final long startNs = System.nanoTime();
            final long messages = reader.replay(parser);
            final long elapsedNs = System.nanoTime() - startNs;

            final double seconds = elapsedNs / 1e9;
            final double rate = messages / seconds;
            System.out.printf("capture      : %s (%,d bytes)%n", capture, reader.sizeBytes());
            System.out.printf("messages     : %,d%n", messages);
            System.out.printf("elapsed      : %.3f s%n", seconds);
            System.out.printf("throughput   : %,.0f msgs/sec%n", rate);
            System.out.printf("instruments  : %,d%n", books.instrumentCount());
            System.out.printf("open orders  : %,d%n", pool.liveOrders());
            System.out.println(books.statsLine());
        }
    }

    private Replay() {
    }
}
