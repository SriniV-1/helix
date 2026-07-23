package dev.srini.helix.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.srini.helix.book.MatchingEngine;
import dev.srini.helix.book.OrderBook;
import dev.srini.helix.book.Pool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

/**
 * Local HTTP server that runs the real Java matching engine behind a JSON API and
 * serves the demo page. When the page is opened <em>from this server</em>
 * (http://localhost:8080), the order book, the live flow, and the Buy/Sell panel
 * are all driven by {@link OrderBook} + {@link MatchingEngine} in this JVM — not
 * the in-browser JS port. (GitHub Pages can only host static files, so the public
 * demo falls back to the JS simulation.)
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/state} — book snapshot, recent trades, and counters.</li>
 *   <li>{@code POST /api/order} — {@code {"side":"B|S","price":100.02,"size":500}};
 *       runs the order through the matching engine, returns fills/residual.</li>
 *   <li>{@code POST /api/pace}  — {@code {"ms":240}}; 0 pauses the synthetic flow.</li>
 *   <li>{@code GET  /} — serves {@code docs/index.html}.</li>
 * </ul>
 *
 * <p>The book is not thread-safe, so every access — the background flow tick, a
 * snapshot, and a user order — is serialized on one monitor. The demo's event
 * rate is low, so that is more than fast enough and keeps correctness obvious.
 */
public final class DemoServer {

    private static final int DEPTH = 8;
    private static final int TICK = 100;          // 1 cent in ITCH Price(4)
    private static final int OPEN = 100_0000;      // $100.00

    private final Object lock = new Object();
    private final Pool pool = new Pool(1 << 16, 1 << 12);
    private final OrderBook book = new OrderBook(pool, 1 << 16);
    private final MatchingEngine engine = new MatchingEngine(book);
    private final Random rng = new Random(42);

    private final ArrayDeque<long[]> trades = new ArrayDeque<>();   // [tMillis, priceE4, shares, side 0=B/1=S]
    private final ArrayDeque<Long> liveRefs = new ArrayDeque<>();

    private long nextRef = 1;
    private int fairE4 = OPEN;
    private int lastE4 = OPEN;
    private long volume;
    private long ordersProcessed;
    private long tradesPrinted;
    private volatile int paceMs = 240;

    private final MatchingEngine.FillHandler onFill = (aggr, resting, side, shares, priceE4) -> {
        lastE4 = priceE4;
        volume += shares;
        tradesPrinted++;
        trades.addFirst(new long[]{System.currentTimeMillis(), priceE4, shares, side == OrderBook.BUY ? 0 : 1});
        while (trades.size() > 60) {
            trades.removeLast();
        }
    };

    public static void main(String[] args) throws IOException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new DemoServer().start(port);
    }

    private DemoServer() {
        seedBook();
    }

    public void start(int port) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/state", this::handleState);
        server.createContext("/api/order", this::handleOrder);
        server.createContext("/api/pace", this::handlePace);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(4));

        final Thread flow = new Thread(this::flowLoop, "synthetic-flow");
        flow.setDaemon(true);
        flow.start();

        server.start();
        System.out.println("Helix demo server on http://localhost:" + port
                + "  (open it and the Buy/Sell panel drives the real Java engine)");
    }

    // ------------------------------------------------------------------
    // Live synthetic flow through the real matching engine
    // ------------------------------------------------------------------

    private void seedBook() {
        for (int i = 1; i <= 14; i++) {
            final int n = 1 + rng.nextInt(3);
            for (int k = 0; k < n; k++) {
                track(add(OrderBook.BUY, (2 + rng.nextInt(18)) * 100, OPEN - i * TICK));
                track(add(OrderBook.SELL, (2 + rng.nextInt(18)) * 100, OPEN + i * TICK));
            }
        }
    }

    private void flowLoop() {
        while (true) {
            final int pace = paceMs;
            try {
                Thread.sleep(pace > 0 ? pace : 120);
            } catch (InterruptedException e) {
                return;
            }
            if (pace == 0) {
                continue;
            }
            synchronized (lock) {
                step();
            }
        }
    }

    private void step() {
        ordersProcessed++;
        fairE4 += (rng.nextBoolean() ? 1 : -1) * rng.nextInt(2) * TICK;
        final int roll = rng.nextInt(100);
        if (roll < 14) {
            engine.submitMarket(nextRef++, side(), (1 + rng.nextInt(6)) * 100, onFill);
        } else if (roll < 60) {
            final byte s = side();
            final int drift = rng.nextInt(5) - 2;
            final int px = clampTick(fairE4 + (s == OrderBook.BUY ? drift : -drift) * TICK);
            final long ref = nextRef++;
            engine.submitLimit(ref, s, (1 + rng.nextInt(14)) * 100, px, onFill);
            track(ref);
        } else if (roll < 80) {
            // Passive-ish order: still routed through the engine so it matches
            // rather than resting into a crossed book if the touch has drifted.
            final byte s = side();
            final int off = 1 + rng.nextInt(10);
            final int px = clampTick(fairE4 + (s == OrderBook.BUY ? -off : off) * TICK);
            final long ref = nextRef++;
            engine.submitLimit(ref, s, (2 + rng.nextInt(16)) * 100, px, onFill);
            track(ref);
        } else if (!liveRefs.isEmpty()) {
            book.delete(liveRefs.pollFirst());
        }
    }

    private long add(byte side, int shares, int priceE4) {
        final long ref = nextRef++;
        book.add(ref, side, shares, priceE4);
        return ref;
    }

    private void track(long ref) {
        liveRefs.addLast(ref);
        while (liveRefs.size() > 4000) {
            liveRefs.pollFirst();
        }
    }

    private byte side() {
        return rng.nextBoolean() ? OrderBook.BUY : OrderBook.SELL;
    }

    private static int clampTick(int priceE4) {
        return Math.max(TICK, priceE4);
    }

    // ------------------------------------------------------------------
    // HTTP handlers
    // ------------------------------------------------------------------

    private void handleState(HttpExchange ex) throws IOException {
        final String json;
        synchronized (lock) {
            json = stateJson();
        }
        sendJson(ex, 200, json);
    }

    private void handleOrder(HttpExchange ex) throws IOException {
        if (preflight(ex)) {
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"POST only\"}");
            return;
        }
        final String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        final byte s = body.contains("\"S\"") || body.contains("\"side\":\"S\"") ? OrderBook.SELL : OrderBook.BUY;
        final double price = num(body, "price", 0);
        final int size = (int) num(body, "size", 0);
        final int priceE4 = (int) Math.round(price * 10000);
        if (priceE4 <= 0 || size <= 0) {
            sendJson(ex, 400, "{\"error\":\"price and size must be positive\"}");
            return;
        }
        final int residual;
        final int filled;
        final long notionalCents;
        synchronized (lock) {
            final long ref = nextRef++;
            final long[] acc = new long[1];   // total shares*priceE4 across the fills
            final MatchingEngine.FillHandler h = (aggr, resting, side2, shares, px) -> {
                onFill.onFill(aggr, resting, side2, shares, px);
                acc[0] += (long) shares * px;
            };
            residual = engine.submitLimit(ref, s, size, priceE4, h);
            if (residual > 0) {
                track(ref);
            }
            filled = size - residual;
            notionalCents = acc[0] / 100;     // priceE4 is hundredths of a cent
        }
        sendJson(ex, 200, "{\"filled\":" + filled + ",\"residual\":" + residual
                + ",\"last\":" + cents(lastE4) + ",\"notional\":" + notionalCents + "}");
    }

    private void handlePace(HttpExchange ex) throws IOException {
        if (preflight(ex)) {
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"error\":\"POST only\"}");
            return;
        }
        final String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        paceMs = Math.max(0, (int) num(body, "ms", 240));
        sendJson(ex, 200, "{\"paceMs\":" + paceMs + "}");
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        final String path = ex.getRequestURI().getPath();
        if ("/favicon.ico".equals(path)) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"/".equals(path)) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        final Path page = Path.of("docs/index.html");
        if (!Files.exists(page)) {
            sendText(ex, 500, "docs/index.html not found — run from the repo root");
            return;
        }
        final byte[] html = Files.readAllBytes(page);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(html);
        }
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    private String stateJson() {
        final int bb = book.bestBid();
        final int ba = book.bestAsk();
        final StringBuilder sb = new StringBuilder(2048);
        sb.append('{');
        sb.append("\"last\":").append(cents(lastE4));
        sb.append(",\"open\":").append(cents(OPEN));
        sb.append(",\"bestBid\":").append(bb < 0 ? "null" : cents(bb));
        sb.append(",\"bestAsk\":").append(ba < 0 ? "null" : cents(ba));
        appendLevels(sb, ",\"bids\":", book.topLevels(OrderBook.BUY, DEPTH));
        appendLevels(sb, ",\"asks\":", book.topLevels(OrderBook.SELL, DEPTH));
        sb.append(",\"trades\":[");
        boolean first = true;
        for (final long[] t : trades) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('[').append(t[0]).append(',').append(cents((int) t[1]))
                    .append(',').append(t[2]).append(',').append(t[3]).append(']');
        }
        sb.append(']');
        sb.append(",\"stats\":{\"orders\":").append(ordersProcessed)
                .append(",\"trades\":").append(tradesPrinted)
                .append(",\"resting\":").append(book.openOrders())
                .append(",\"volume\":").append(volume).append('}');
        sb.append(",\"paceMs\":").append(paceMs);
        sb.append('}');
        return sb.toString();
    }

    private static void appendLevels(StringBuilder sb, String key, List<int[]> levels) {
        sb.append(key).append('[');
        for (int i = 0; i < levels.size(); i++) {
            final int[] l = levels.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(cents(l[0])).append(',').append(l[1]).append(',').append(l[2]).append(']');
        }
        sb.append(']');
    }

    /** ITCH Price(4) → integer cents for the UI. */
    private static int cents(int priceE4) {
        return priceE4 / 100;
    }

    /** Extract a numeric JSON field value by key; tolerant of spacing. */
    private static double num(String body, String key, double dflt) {
        final int k = body.indexOf("\"" + key + "\"");
        if (k < 0) {
            return dflt;
        }
        int i = body.indexOf(':', k) + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < body.length() && "+-.0123456789eE".indexOf(body.charAt(j)) >= 0) {
            j++;
        }
        try {
            return Double.parseDouble(body.substring(i, j));
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    /** Answer a CORS preflight; returns true if the request was an OPTIONS handled here. */
    private static boolean preflight(HttpExchange ex) throws IOException {
        if (!"OPTIONS".equals(ex.getRequestMethod())) {
            return false;
        }
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.sendResponseHeaders(204, -1);
        ex.close();
        return true;
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        final byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        final byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
