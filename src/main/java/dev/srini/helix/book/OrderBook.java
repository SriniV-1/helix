package dev.srini.helix.book;

import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Price-time-priority limit order book, one instance per instrument.
 *
 * <p>Design goals, in order: correctness against ITCH replay, then zero
 * steady-state allocation. Orders and price levels are pooled and recycled;
 * each price level keeps its resting orders in an intrusive doubly-linked
 * FIFO queue so add/remove are O(1) pointer swaps.
 *
 * <p>Best-price maintenance is incremental on add; on removal of the best
 * level the book scans its level map for the next best. That scan is the
 * known non-constant cost in this version and is the first optimization
 * target (array-backed price ladder) once replay correctness is locked in.
 */
public final class OrderBook {

    public static final byte BUY = 'B';
    public static final byte SELL = 'S';

    private static final int NO_PRICE = -1;

    private final Long2ObjectHashMap<Order> ordersByRef;
    private final Int2ObjectHashMap<PriceLevel> bidLevels;
    private final Int2ObjectHashMap<PriceLevel> askLevels;
    private final Pool pool;

    private int bestBid = NO_PRICE;
    private int bestAsk = NO_PRICE;

    public OrderBook(Pool pool, int expectedOrders) {
        this.pool = pool;
        this.ordersByRef = new Long2ObjectHashMap<>(expectedOrders, 0.65f);
        this.bidLevels = new Int2ObjectHashMap<>(256, 0.65f);
        this.askLevels = new Int2ObjectHashMap<>(256, 0.65f);
    }

    // ------------------------------------------------------------------
    // Mutations (driven by ITCH events)
    // ------------------------------------------------------------------

    public void add(long orderRef, byte side, int shares, int priceE4) {
        final Order order = pool.takeOrder();
        order.ref = orderRef;
        order.side = side;
        order.shares = shares;
        order.priceE4 = priceE4;

        final Int2ObjectHashMap<PriceLevel> levels = levels(side);
        PriceLevel level = levels.get(priceE4);
        if (level == null) {
            level = pool.takeLevel();
            level.priceE4 = priceE4;
            levels.put(priceE4, level);
            if (side == BUY) {
                if (bestBid == NO_PRICE || priceE4 > bestBid) {
                    bestBid = priceE4;
                }
            } else {
                if (bestAsk == NO_PRICE || priceE4 < bestAsk) {
                    bestAsk = priceE4;
                }
            }
        }
        level.enqueue(order);
        ordersByRef.put(orderRef, order);
    }

    /** Execute {@code shares} against a resting order; removes it when fully filled. */
    public void execute(long orderRef, int shares) {
        final Order order = ordersByRef.get(orderRef);
        if (order == null) {
            return; // order for an instrument/session we did not track
        }
        order.shares -= shares;
        order.level.totalShares -= shares;
        if (order.shares <= 0) {
            remove(order);
        }
    }

    /** Cancel part of a resting order; ITCH sends Delete for full cancels. */
    public void cancel(long orderRef, int canceledShares) {
        final Order order = ordersByRef.get(orderRef);
        if (order == null) {
            return;
        }
        order.shares -= canceledShares;
        order.level.totalShares -= canceledShares;
        if (order.shares <= 0) {
            remove(order);
        }
    }

    public void delete(long orderRef) {
        final Order order = ordersByRef.get(orderRef);
        if (order != null) {
            remove(order);
        }
    }

    /**
     * Replace moves the order to the back of the queue at the new price with a
     * new reference, per ITCH semantics (time priority is lost).
     */
    public void replace(long originalRef, long newRef, int shares, int priceE4) {
        final Order original = ordersByRef.get(originalRef);
        if (original == null) {
            return;
        }
        final byte side = original.side;
        remove(original);
        add(newRef, side, shares, priceE4);
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public int bestBid() {
        return bestBid;
    }

    public int bestAsk() {
        return bestAsk;
    }

    public int sharesAt(byte side, int priceE4) {
        final PriceLevel level = levels(side).get(priceE4);
        return level == null ? 0 : level.totalShares;
    }

    public int ordersAt(byte side, int priceE4) {
        final PriceLevel level = levels(side).get(priceE4);
        return level == null ? 0 : level.orderCount;
    }

    public int openOrders() {
        return ordersByRef.size();
    }

    /**
     * Snapshot the best {@code depth} price levels on a side as {@code [priceE4,
     * totalShares, orderCount]}, best price first. Allocates; intended for
     * out-of-band queries (e.g. a UI), not the hot path.
     */
    public java.util.List<int[]> topLevels(byte side, int depth) {
        final java.util.List<int[]> out = new java.util.ArrayList<>();
        for (final PriceLevel lv : levels(side).values()) {
            out.add(new int[]{lv.priceE4, lv.totalShares, lv.orderCount});
        }
        final boolean bid = side == BUY;
        out.sort((a, b) -> bid ? Integer.compare(b[0], a[0]) : Integer.compare(a[0], b[0]));
        return out.size() > depth ? new java.util.ArrayList<>(out.subList(0, depth)) : out;
    }

    /** First order in time priority at a price, or 0 if the level is empty. */
    public long frontOrderAt(byte side, int priceE4) {
        final PriceLevel level = levels(side).get(priceE4);
        return level == null || level.head == null ? 0 : level.head.ref;
    }

    // ------------------------------------------------------------------
    // Matching support (package-private; used by MatchingEngine)
    // ------------------------------------------------------------------

    /** Resting order with best price and oldest time on {@code side}, or null if empty. */
    Order bestRestingOrder(byte side) {
        final int price = side == BUY ? bestBid : bestAsk;
        if (price == NO_PRICE) {
            return null;
        }
        final PriceLevel level = levels(side).get(price);
        return level == null ? null : level.head;
    }

    /**
     * Fill {@code shares} against a resting order, removing it once depleted and
     * promoting the next best level if that empties the current one.
     */
    void fillResting(Order resting, int shares) {
        resting.shares -= shares;
        resting.level.totalShares -= shares;
        if (resting.shares <= 0) {
            remove(resting);
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Int2ObjectHashMap<PriceLevel> levels(byte side) {
        return side == BUY ? bidLevels : askLevels;
    }

    private void remove(Order order) {
        final PriceLevel level = order.level;
        final byte side = order.side;
        final int price = level.priceE4;

        level.unlink(order);
        ordersByRef.remove(order.ref);
        pool.recycleOrder(order);

        if (level.orderCount == 0) {
            levels(side).remove(price);
            pool.recycleLevel(level);
            if (side == BUY && price == bestBid) {
                bestBid = scanBest(bidLevels, true);
            } else if (side == SELL && price == bestAsk) {
                bestAsk = scanBest(askLevels, false);
            }
        }
    }

    private static int scanBest(Int2ObjectHashMap<PriceLevel> levels, boolean highest) {
        if (levels.isEmpty()) {
            return NO_PRICE;
        }
        int best = highest ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        final var it = levels.keySet().iterator();
        while (it.hasNext()) {
            final int price = it.nextInt();
            if (highest ? price > best : price < best) {
                best = price;
            }
        }
        return best;
    }
}
