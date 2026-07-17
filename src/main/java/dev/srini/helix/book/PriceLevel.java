package dev.srini.helix.book;

/**
 * One price level: aggregate shares/order count plus the head/tail of the
 * intrusive FIFO queue of resting orders. Pooled and recycled.
 */
final class PriceLevel {
    int priceE4;
    int totalShares;
    int orderCount;

    Order head;
    Order tail;

    /** Free-list link used only while the level is in the pool. */
    PriceLevel poolNext;

    void enqueue(Order order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalShares += order.shares;
        orderCount++;
    }

    void unlink(Order order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        totalShares -= order.shares;
        orderCount--;
    }
}
