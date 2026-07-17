package dev.srini.helix.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies continuous matching: price-time priority, partial fills, multi-level
 * sweeps, resting residual, and market-order behavior. These are the assertions
 * that make "matching engine" an accurate description.
 */
class MatchingEngineTest {

    private OrderBook book;
    private MatchingEngine engine;
    private List<String> fills;
    private MatchingEngine.FillHandler recorder;

    @BeforeEach
    void setUp() {
        book = new OrderBook(new Pool(4096, 512), 4096);
        engine = new MatchingEngine(book);
        fills = new ArrayList<>();
        recorder = (aggressor, resting, side, shares, price) ->
                fills.add(aggressor + "x" + resting + " " + shares + "@" + price);
    }

    @Test
    void crossingBuyLiftsLowestAskFirst() {
        book.add(1, OrderBook.SELL, 100, 101_0000);
        book.add(2, OrderBook.SELL, 100, 102_0000);

        final int residual = engine.submitLimit(99, OrderBook.BUY, 100, 101_0000, recorder);

        assertEquals(0, residual);
        assertEquals(List.of("99x1 100@1010000"), fills);
        assertEquals(102_0000, book.bestAsk());        // 101 level consumed
        assertEquals(-1, book.bestBid());              // nothing rested
    }

    @Test
    void timePriorityFillsOldestRestingOrderFirst() {
        book.add(1, OrderBook.SELL, 100, 101_0000);
        book.add(2, OrderBook.SELL, 100, 101_0000);    // same price, later

        engine.submitLimit(99, OrderBook.BUY, 150, 101_0000, recorder);

        // Order 1 fully (100), order 2 partially (50), in arrival order.
        assertEquals(List.of("99x1 100@1010000", "99x2 50@1010000"), fills);
        assertEquals(50, book.sharesAt(OrderBook.SELL, 101_0000));
        assertEquals(2, book.frontOrderAt(OrderBook.SELL, 101_0000));
    }

    @Test
    void aggressiveOrderSweepsMultiplePriceLevels() {
        book.add(1, OrderBook.SELL, 100, 101_0000);
        book.add(2, OrderBook.SELL, 100, 102_0000);
        book.add(3, OrderBook.SELL, 100, 103_0000);

        final int residual = engine.submitLimit(99, OrderBook.BUY, 250, 102_0000, recorder);

        // Takes 101 and 102 fully; 103 is above the limit, so 50 residual rests.
        assertEquals(50, residual);
        assertEquals(List.of("99x1 100@1010000", "99x2 100@1020000"), fills);
        assertEquals(103_0000, book.bestAsk());
        assertEquals(102_0000, book.bestBid());         // residual rested at limit
        assertEquals(50, book.sharesAt(OrderBook.BUY, 102_0000));
    }

    @Test
    void nonCrossingLimitRestsWithoutTrading() {
        book.add(1, OrderBook.SELL, 100, 105_0000);

        final int residual = engine.submitLimit(99, OrderBook.BUY, 100, 100_0000, recorder);

        assertEquals(100, residual);
        assertTrue(fills.isEmpty());
        assertEquals(100_0000, book.bestBid());
        assertEquals(105_0000, book.bestAsk());
    }

    @Test
    void crossingSellHitsHighestBidFirst() {
        book.add(1, OrderBook.BUY, 100, 100_0000);
        book.add(2, OrderBook.BUY, 100, 99_0000);

        engine.submitLimit(99, OrderBook.SELL, 100, 99_0000, recorder);

        assertEquals(List.of("99x1 100@1000000"), fills);
        assertEquals(99_0000, book.bestBid());
    }

    @Test
    void marketOrderTakesLiquidityUntilExhaustedThenStops() {
        book.add(1, OrderBook.SELL, 100, 101_0000);
        book.add(2, OrderBook.SELL, 100, 102_0000);

        final int unfilled = engine.submitMarket(99, OrderBook.BUY, 500, recorder);

        assertEquals(300, unfilled);                    // only 200 available
        assertEquals(200, engine.lastFilledShares());
        assertEquals(-1, book.bestAsk());               // book emptied
        assertEquals(-1, book.bestBid());               // market residual does not rest
    }

    @Test
    void tradesPrintAtRestingPriceNotAggressorPrice() {
        book.add(1, OrderBook.SELL, 100, 101_0000);

        engine.submitLimit(99, OrderBook.BUY, 100, 105_0000, recorder); // willing to pay 105

        assertEquals(List.of("99x1 100@1010000"), fills); // fills at the resting 101
    }

    @Test
    void conservationOfSharesAcrossAMatch() {
        book.add(1, OrderBook.SELL, 100, 101_0000);
        book.add(2, OrderBook.SELL, 250, 101_0000);

        engine.submitLimit(99, OrderBook.BUY, 300, 101_0000, recorder);

        // 300 in: 100 + 200 filled, 50 of order 2 remains resting.
        assertEquals(300, engine.lastFilledShares());
        assertEquals(50, book.sharesAt(OrderBook.SELL, 101_0000));
        assertEquals(0, engine.lastRestedShares());
    }
}
