package dev.srini.helix.book;

/**
 * A resting order. Pooled and recycled; also a node in its price level's
 * intrusive doubly-linked FIFO queue.
 */
final class Order {
    long ref;
    byte side;
    int shares;
    int priceE4;

    PriceLevel level;
    Order prev;
    Order next;

    /** Free-list link used only while the order is in the pool. */
    Order poolNext;
}
