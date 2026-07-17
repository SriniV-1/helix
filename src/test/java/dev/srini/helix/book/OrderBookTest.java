package dev.srini.helix.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness of price-time-priority maintenance under the full order lifecycle.
 * These assertions are the reference the replay harness cross-checks against.
 */
class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook(new Pool(1024, 256), 1024);
    }

    @Test
    void bestBidAndAskTrackTopOfBook() {
        book.add(1, OrderBook.BUY, 100, 100_0000);
        book.add(2, OrderBook.BUY, 100, 101_0000);
        book.add(3, OrderBook.SELL, 100, 103_0000);
        book.add(4, OrderBook.SELL, 100, 102_0000);

        assertEquals(101_0000, book.bestBid());
        assertEquals(102_0000, book.bestAsk());
    }

    @Test
    void timePriorityIsFifoWithinAPriceLevel() {
        book.add(10, OrderBook.BUY, 100, 100_0000);
        book.add(11, OrderBook.BUY, 100, 100_0000);
        book.add(12, OrderBook.BUY, 100, 100_0000);

        assertEquals(3, book.ordersAt(OrderBook.BUY, 100_0000));
        assertEquals(10, book.frontOrderAt(OrderBook.BUY, 100_0000));

        book.delete(10);
        assertEquals(11, book.frontOrderAt(OrderBook.BUY, 100_0000));
    }

    @Test
    void partialExecuteReducesSharesButKeepsOrder() {
        book.add(20, OrderBook.SELL, 500, 200_0000);
        book.execute(20, 200);

        assertEquals(300, book.sharesAt(OrderBook.SELL, 200_0000));
        assertEquals(1, book.ordersAt(OrderBook.SELL, 200_0000));
    }

    @Test
    void fullExecuteRemovesOrderAndCollapsesEmptyLevel() {
        book.add(30, OrderBook.SELL, 100, 200_0000);
        book.add(31, OrderBook.SELL, 100, 201_0000);
        book.execute(30, 100);

        assertEquals(0, book.ordersAt(OrderBook.SELL, 200_0000));
        assertEquals(201_0000, book.bestAsk());
    }

    @Test
    void deletingBestBidPromotesNextLevel() {
        book.add(40, OrderBook.BUY, 100, 100_0000);
        book.add(41, OrderBook.BUY, 100, 99_0000);
        book.delete(40);

        assertEquals(99_0000, book.bestBid());
    }

    @Test
    void replaceLosesTimePriority() {
        book.add(50, OrderBook.BUY, 100, 100_0000);
        book.add(51, OrderBook.BUY, 100, 100_0000);
        book.replace(50, 52, 100, 100_0000);

        // 50 removed, 51 now front, 52 appended behind it.
        assertEquals(51, book.frontOrderAt(OrderBook.BUY, 100_0000));
        assertEquals(2, book.ordersAt(OrderBook.BUY, 100_0000));
    }

    @Test
    void cancelDownToZeroRemovesOrder() {
        book.add(60, OrderBook.BUY, 100, 100_0000);
        book.cancel(60, 100);

        assertEquals(0, book.openOrders());
        assertEquals(-1, book.bestBid());
    }

    @Test
    void unknownReferenceIsIgnored() {
        book.add(70, OrderBook.BUY, 100, 100_0000);
        book.execute(999, 100); // no such order
        book.delete(998);

        assertEquals(1, book.openOrders());
        assertEquals(100, book.sharesAt(OrderBook.BUY, 100_0000));
    }
}
