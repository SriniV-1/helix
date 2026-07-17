package dev.srini.helix.book;

import org.agrona.collections.Int2ObjectHashMap;

/**
 * One side of the book as an array-backed price ladder — an <em>evaluated
 * alternative</em> to the hash-map level store in {@link OrderBook}, kept here
 * with its own tests for reference.
 *
 * <p>Intraday, an instrument's resting prices cluster in a tight band, so levels
 * live in a contiguous {@code PriceLevel[]} indexed by tick offset from a moving
 * base. That makes level lookup a single array read and the next-best search on
 * level collapse a short, cache-friendly walk over adjacent slots. Prices that
 * fall outside the window, or are not aligned to the tick, fall back to a hash
 * map so correctness never depends on the fast path.
 *
 * <p><b>Benchmark outcome (JMH, this repo):</b> the ladder roughly halved single-
 * book add/cancel p50 latency (83&nbsp;ns&nbsp;&rarr;&nbsp;42&nbsp;ns) but did
 * <em>not</em> win the full replay, which runs ~500 books at once: one sparse
 * array per side per book adds enough cache pressure that end-to-end throughput
 * came in at/below the hash-map book (~4.5M vs ~5.0M msgs/sec), and no single
 * window size won both the deep-single-book and many-shallow-book cases. The
 * hash-map book is therefore the production store; this class documents the
 * tradeoff rather than hiding it.
 *
 * <p>The ladder is one-sided: {@code better} means higher for bids and lower for
 * asks, so the same code drives both by flipping a sign at construction.
 */
final class PriceLadder {

    /** Tick size in ITCH Price(4) units — one cent. */
    static final int TICK = 100;
    // A band, not the whole price axis: intraday depth clusters within a few
    // dollars of the touch, so a small window stays cache-resident across the
    // many books in a session while overflow catches the rare far-out level.
    private static final int WINDOW = 1 << 11;   // 2,048 ticks (~$20 band)
    static final int NO_PRICE = -1;

    private final boolean bidSide;               // true: higher price is better
    private final PriceLevel[] slots = new PriceLevel[WINDOW];
    private final Int2ObjectHashMap<PriceLevel> overflow = new Int2ObjectHashMap<>(64, 0.65f);

    private int base = Integer.MIN_VALUE;        // priceE4 mapped to slot 0
    private int residue;                         // priceE4 % TICK for aligned prices
    private int count;                           // populated levels (array + overflow)
    private int bestIdx = NO_PRICE;              // slot index of best price, or NO_PRICE

    PriceLadder(boolean bidSide) {
        this.bidSide = bidSide;
    }

    // ------------------------------------------------------------------

    private boolean aligned(int priceE4) {
        return base != Integer.MIN_VALUE
                && Math.floorMod(priceE4, TICK) == residue;
    }

    private int index(int priceE4) {
        return (priceE4 - base) / TICK;
    }

    private boolean inWindow(int idx) {
        return idx >= 0 && idx < WINDOW;
    }

    PriceLevel get(int priceE4) {
        if (aligned(priceE4)) {
            final int idx = index(priceE4);
            if (inWindow(idx)) {
                return slots[idx];
            }
        }
        return overflow.get(priceE4);
    }

    void put(int priceE4, PriceLevel level) {
        if (base == Integer.MIN_VALUE) {
            // Center the window on the first price so the band brackets it.
            residue = Math.floorMod(priceE4, TICK);
            base = priceE4 - (WINDOW / 2) * TICK;
        }
        if (aligned(priceE4)) {
            final int idx = index(priceE4);
            if (inWindow(idx)) {
                slots[idx] = level;
                count++;
                if (bestIdx == NO_PRICE || better(idx, bestIdx)) {
                    bestIdx = idx;
                }
                return;
            }
        }
        overflow.put(priceE4, level);
        count++;
    }

    void remove(int priceE4) {
        if (aligned(priceE4)) {
            final int idx = index(priceE4);
            if (inWindow(idx)) {
                slots[idx] = null;
                count--;
                if (idx == bestIdx) {
                    bestIdx = scanNextBest(idx);
                }
                return;
            }
        }
        if (overflow.remove(priceE4) != null) {
            count--;
        }
    }

    /** Best price on this side, or {@link #NO_PRICE} if empty. */
    int bestPrice() {
        if (bestIdx != NO_PRICE) {
            final int arrayBest = base + bestIdx * TICK;
            if (overflow.isEmpty()) {
                return arrayBest;
            }
            return betterPrice(arrayBest, overflowBest());
        }
        return overflow.isEmpty() ? NO_PRICE : overflowBest();
    }

    boolean isEmpty() {
        return count == 0;
    }

    // ------------------------------------------------------------------

    /** Walk from a collapsed best slot toward the interior for the next populated level. */
    private int scanNextBest(int from) {
        if (bidSide) {
            for (int i = from - 1; i >= 0; i--) {
                if (slots[i] != null) {
                    return i;
                }
            }
        } else {
            for (int i = from + 1; i < WINDOW; i++) {
                if (slots[i] != null) {
                    return i;
                }
            }
        }
        return NO_PRICE;
    }

    private boolean better(int idxA, int idxB) {
        return bidSide ? idxA > idxB : idxA < idxB;
    }

    private int betterPrice(int a, int b) {
        return bidSide ? Math.max(a, b) : Math.min(a, b);
    }

    private int overflowBest() {
        int best = bidSide ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        final var it = overflow.keySet().iterator();
        while (it.hasNext()) {
            final int px = it.nextInt();
            if (bidSide ? px > best : px < best) {
                best = px;
            }
        }
        return best;
    }
}
