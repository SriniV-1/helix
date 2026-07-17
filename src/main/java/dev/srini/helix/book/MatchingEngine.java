package dev.srini.helix.book;

/**
 * Continuous limit-order matching engine over an {@link OrderBook}.
 *
 * <p>Where the ITCH replay path <em>reconstructs</em> books from already-matched
 * market data, this engine <em>produces</em> matches: an inbound aggressive order
 * is crossed against the opposite side of the book in strict price-time priority,
 * emitting a fill per resting order it consumes. A crossing buy lifts the lowest
 * asks first and, within a price, the oldest resting order first; a crossing sell
 * hits the highest bids first. Trades print at the resting (passive) order's price,
 * matching exchange convention.
 *
 * <p>Fills are reported through a {@link FillHandler} the caller supplies; the
 * engine allocates nothing per order or per fill.
 */
public final class MatchingEngine {

    /** Receives one callback per resting order consumed by an aggressive order. */
    @FunctionalInterface
    public interface FillHandler {
        void onFill(long aggressorRef, long restingRef, byte aggressorSide,
                    int shares, int priceE4);
    }

    private static final FillHandler NO_OP = (a, r, s, sh, p) -> {
    };

    private final OrderBook book;

    private long lastFilledShares;
    private long lastRestedShares;

    public MatchingEngine(OrderBook book) {
        this.book = book;
    }

    /**
     * Submit a limit order. It crosses as far as its price allows, then any
     * residual quantity rests on the book keyed by {@code ref}.
     *
     * @return residual shares that rested (0 if fully filled)
     */
    public int submitLimit(long ref, byte side, int shares, int priceE4, FillHandler fills) {
        final int residual = cross(ref, side, shares, priceE4, false, handler(fills));
        if (residual > 0) {
            book.add(ref, side, residual, priceE4);
        }
        lastRestedShares = residual;
        return residual;
    }

    /**
     * Submit a market order. It crosses against all available liquidity at any
     * price; unfilled quantity is discarded (market orders do not rest).
     *
     * @return shares that could not be filled for lack of liquidity
     */
    public int submitMarket(long ref, byte side, int shares, FillHandler fills) {
        final int unfilled = cross(ref, side, shares, 0, true, handler(fills));
        lastRestedShares = 0;
        return unfilled;
    }

    /**
     * Core crossing loop, shared by limit and market submission.
     *
     * @param market when true the price bound is ignored (cross any level)
     * @return quantity left after crossing stops
     */
    private int cross(long ref, byte side, int shares, int limitPriceE4,
                      boolean market, FillHandler fills) {
        final byte restingSide = side == OrderBook.BUY ? OrderBook.SELL : OrderBook.BUY;
        int remaining = shares;
        long filled = 0;

        while (remaining > 0) {
            final Order resting = book.bestRestingOrder(restingSide);
            if (resting == null) {
                break; // no liquidity left on the opposite side
            }
            if (!market && !crosses(side, limitPriceE4, resting.priceE4)) {
                break; // best opposite price is worse than our limit
            }
            final int fill = Math.min(remaining, resting.shares);
            fills.onFill(ref, resting.ref, side, fill, resting.priceE4);
            book.fillResting(resting, fill);
            remaining -= fill;
            filled += fill;
        }

        lastFilledShares = filled;
        return remaining;
    }

    /** A buy crosses when its limit is at or above the ask; a sell, at or below the bid. */
    private static boolean crosses(byte aggressorSide, int limitPriceE4, int restingPriceE4) {
        return aggressorSide == OrderBook.BUY
                ? limitPriceE4 >= restingPriceE4
                : limitPriceE4 <= restingPriceE4;
    }

    private static FillHandler handler(FillHandler fills) {
        return fills == null ? NO_OP : fills;
    }

    /** Shares matched by the most recent submission. */
    public long lastFilledShares() {
        return lastFilledShares;
    }

    /** Shares that rested on the book from the most recent submission. */
    public long lastRestedShares() {
        return lastRestedShares;
    }
}
