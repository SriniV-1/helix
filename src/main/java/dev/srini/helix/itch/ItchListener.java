package dev.srini.helix.itch;

/**
 * Callback interface for decoded ITCH 5.0 messages.
 *
 * <p>All arguments are primitives so that steady-state parsing allocates nothing.
 * Stock symbols are the raw 8 ASCII bytes of the ITCH field packed big-endian
 * into a {@code long} (right-padded with spaces), which makes them directly
 * comparable and usable as map keys without creating strings.
 *
 * <p>Prices are fixed-point with 4 implied decimal places (the ITCH Price(4)
 * format): a wire value of 1234500 means $123.45.
 */
public interface ItchListener {

    void onSystemEvent(int stockLocate, long timestampNs, byte eventCode);

    void onStockDirectory(int stockLocate, long timestampNs, long symbol);

    void onAddOrder(int stockLocate, long timestampNs, long orderRef, byte side,
                    int shares, long symbol, int priceE4);

    void onOrderExecuted(int stockLocate, long timestampNs, long orderRef,
                         int executedShares, long matchNumber);

    void onOrderExecutedWithPrice(int stockLocate, long timestampNs, long orderRef,
                                  int executedShares, long matchNumber,
                                  byte printable, int priceE4);

    void onOrderCancel(int stockLocate, long timestampNs, long orderRef, int canceledShares);

    void onOrderDelete(int stockLocate, long timestampNs, long orderRef);

    void onOrderReplace(int stockLocate, long timestampNs, long originalOrderRef,
                        long newOrderRef, int shares, int priceE4);

    void onTrade(int stockLocate, long timestampNs, long orderRef, byte side,
                 int shares, long symbol, int priceE4, long matchNumber);

    /** Any message type this listener does not need; default is to ignore it. */
    default void onOther(byte messageType, int stockLocate, long timestampNs) {
    }
}
