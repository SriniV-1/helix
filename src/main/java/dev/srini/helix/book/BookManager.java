package dev.srini.helix.book;

import dev.srini.helix.itch.ItchListener;
import org.agrona.collections.Int2ObjectHashMap;

/**
 * Fans decoded ITCH messages out to a per-instrument {@link OrderBook}, keyed by
 * ITCH stock-locate id. Implements {@link ItchListener} directly so the parser
 * drives book construction with no intermediate objects.
 */
public final class BookManager implements ItchListener {

    private final Int2ObjectHashMap<OrderBook> books = new Int2ObjectHashMap<>();
    private final Pool pool;
    private final int perBookOrders;

    private long messages;
    private long addOrders;
    private long executions;
    private long cancels;
    private long deletes;
    private long replaces;
    private long trades;

    public BookManager(Pool pool, int perBookOrders) {
        this.pool = pool;
        this.perBookOrders = perBookOrders;
    }

    private OrderBook book(int stockLocate) {
        OrderBook book = books.get(stockLocate);
        if (book == null) {
            book = new OrderBook(pool, perBookOrders);
            books.put(stockLocate, book);
        }
        return book;
    }

    public OrderBook bookIfPresent(int stockLocate) {
        return books.get(stockLocate);
    }

    public int instrumentCount() {
        return books.size();
    }

    public long messages() {
        return messages;
    }

    @Override
    public void onSystemEvent(int stockLocate, long timestampNs, byte eventCode) {
        messages++;
    }

    @Override
    public void onStockDirectory(int stockLocate, long timestampNs, long symbol) {
        messages++;
        book(stockLocate); // materialize so locate->book mapping exists early
    }

    @Override
    public void onAddOrder(int stockLocate, long timestampNs, long orderRef, byte side,
                           int shares, long symbol, int priceE4) {
        messages++;
        addOrders++;
        book(stockLocate).add(orderRef, side, shares, priceE4);
    }

    @Override
    public void onOrderExecuted(int stockLocate, long timestampNs, long orderRef,
                                int executedShares, long matchNumber) {
        messages++;
        executions++;
        book(stockLocate).execute(orderRef, executedShares);
    }

    @Override
    public void onOrderExecutedWithPrice(int stockLocate, long timestampNs, long orderRef,
                                         int executedShares, long matchNumber,
                                         byte printable, int priceE4) {
        messages++;
        executions++;
        book(stockLocate).execute(orderRef, executedShares);
    }

    @Override
    public void onOrderCancel(int stockLocate, long timestampNs, long orderRef, int canceledShares) {
        messages++;
        cancels++;
        book(stockLocate).cancel(orderRef, canceledShares);
    }

    @Override
    public void onOrderDelete(int stockLocate, long timestampNs, long orderRef) {
        messages++;
        deletes++;
        book(stockLocate).delete(orderRef);
    }

    @Override
    public void onOrderReplace(int stockLocate, long timestampNs, long originalOrderRef,
                               long newOrderRef, int shares, int priceE4) {
        messages++;
        replaces++;
        book(stockLocate).replace(originalOrderRef, newOrderRef, shares, priceE4);
    }

    @Override
    public void onTrade(int stockLocate, long timestampNs, long orderRef, byte side,
                        int shares, long symbol, int priceE4, long matchNumber) {
        messages++;
        trades++;
        // Non-displayable trades do not rest on the book; counted for stats only.
    }

    @Override
    public void onOther(byte messageType, int stockLocate, long timestampNs) {
        messages++;
    }

    public String statsLine() {
        return String.format(
                "messages=%d add=%d exec=%d cancel=%d delete=%d replace=%d trade=%d instruments=%d",
                messages, addOrders, executions, cancels, deletes, replaces, trades, books.size());
    }
}
