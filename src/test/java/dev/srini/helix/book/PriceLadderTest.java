package dev.srini.helix.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Exercises the array fast path and the hash-map fallback of {@link PriceLadder},
 * including best-price selection when populated levels straddle both.
 */
class PriceLadderTest {

    private static PriceLevel level(int priceE4) {
        final PriceLevel l = new PriceLevel();
        l.priceE4 = priceE4;
        return l;
    }

    @Test
    void askLadderReturnsLowestPriceAndPromotesOnRemove() {
        final PriceLadder asks = new PriceLadder(false);
        asks.put(101_0000, level(101_0000));
        asks.put(100_0000, level(100_0000));
        asks.put(102_0000, level(102_0000));

        assertEquals(100_0000, asks.bestPrice());
        asks.remove(100_0000);
        assertEquals(101_0000, asks.bestPrice());
    }

    @Test
    void bidLadderReturnsHighestPrice() {
        final PriceLadder bids = new PriceLadder(true);
        bids.put(99_0000, level(99_0000));
        bids.put(100_0000, level(100_0000));
        assertEquals(100_0000, bids.bestPrice());
    }

    @Test
    void unalignedPriceFallsBackToOverflowButStaysRetrievable() {
        final PriceLadder asks = new PriceLadder(false);
        asks.put(100_0000, level(100_0000));       // sets base + residue 0
        final PriceLevel odd = level(100_0050);    // half-cent: residue != 0
        asks.put(100_0050, odd);

        assertSame(odd, asks.get(100_0050));        // served from overflow
        assertEquals(100_0000, asks.bestPrice());   // aligned level still best
    }

    @Test
    void outOfWindowPriceFallsBackAndCanBecomeBest() {
        final PriceLadder bids = new PriceLadder(true);
        bids.put(100_0000, level(100_0000));        // base centers window here
        final int farAbove = 100_0000 + (1 << 20) * PriceLadder.TICK; // beyond window
        final PriceLevel far = level(farAbove);
        bids.put(farAbove, far);

        assertSame(far, bids.get(farAbove));
        assertEquals(farAbove, bids.bestPrice());   // overflow price wins for a bid
        bids.remove(farAbove);
        assertEquals(100_0000, bids.bestPrice());
    }

    @Test
    void emptyLadderReportsNoPrice() {
        final PriceLadder asks = new PriceLadder(false);
        assertEquals(PriceLadder.NO_PRICE, asks.bestPrice());
        assertNull(asks.get(100_0000));
    }
}
