package dev.srini.helix.book;

/**
 * Free-list pools for {@link Order} and {@link PriceLevel} objects, shared by
 * all books in a session so recycled capacity follows the day's hot symbols.
 *
 * <p>Grows by allocating on empty take (warm-up cost only); steady state after
 * the morning burst recycles without allocating.
 */
public final class Pool {

    private Order freeOrders;
    private PriceLevel freeLevels;

    private int liveOrders;
    private int pooledOrders;

    public Pool(int prewarmOrders, int prewarmLevels) {
        for (int i = 0; i < prewarmOrders; i++) {
            recycleOrder(new Order());
        }
        pooledOrders = prewarmOrders;
        for (int i = 0; i < prewarmLevels; i++) {
            recycleLevel(new PriceLevel());
        }
        liveOrders = 0;
    }

    Order takeOrder() {
        Order order = freeOrders;
        if (order == null) {
            order = new Order();
            pooledOrders++;
        } else {
            freeOrders = order.poolNext;
            order.poolNext = null;
        }
        liveOrders++;
        return order;
    }

    void recycleOrder(Order order) {
        order.level = null;
        order.prev = null;
        order.next = null;
        order.poolNext = freeOrders;
        freeOrders = order;
        liveOrders--;
    }

    PriceLevel takeLevel() {
        PriceLevel level = freeLevels;
        if (level == null) {
            level = new PriceLevel();
        } else {
            freeLevels = level.poolNext;
            level.poolNext = null;
        }
        level.totalShares = 0;
        level.orderCount = 0;
        level.head = null;
        level.tail = null;
        return level;
    }

    void recycleLevel(PriceLevel level) {
        level.head = null;
        level.tail = null;
        level.poolNext = freeLevels;
        freeLevels = level;
    }

    public int liveOrders() {
        return liveOrders;
    }

    public int totalAllocated() {
        return pooledOrders;
    }
}
