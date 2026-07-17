package dev.srini.helix.itch;

import org.agrona.DirectBuffer;

import java.nio.ByteOrder;

/**
 * Zero-allocation decoder for NASDAQ TotalView-ITCH 5.0 messages.
 *
 * <p>Operates as a flyweight over a {@link DirectBuffer}: {@link #parse} reads
 * one message in place and dispatches primitive fields to an {@link ItchListener}.
 * No objects are created per message.
 *
 * <p>All ITCH integer fields are big-endian. Every message shares an 11-byte
 * header: type(1), stock locate(2), tracking number(2), timestamp(6, nanos
 * since midnight). Field offsets below are from the start of the message.
 */
public final class ItchParser {

    public static final byte SYSTEM_EVENT = 'S';
    public static final byte STOCK_DIRECTORY = 'R';
    public static final byte ADD_ORDER = 'A';
    public static final byte ADD_ORDER_MPID = 'F';
    public static final byte ORDER_EXECUTED = 'E';
    public static final byte ORDER_EXECUTED_WITH_PRICE = 'C';
    public static final byte ORDER_CANCEL = 'X';
    public static final byte ORDER_DELETE = 'D';
    public static final byte ORDER_REPLACE = 'U';
    public static final byte TRADE = 'P';

    private static final ByteOrder BE = ByteOrder.BIG_ENDIAN;

    private final ItchListener listener;

    public ItchParser(ItchListener listener) {
        this.listener = listener;
    }

    /**
     * Decode a single message starting at {@code offset}.
     *
     * @return the message type byte that was decoded
     */
    public byte parse(DirectBuffer buf, int offset) {
        final byte type = buf.getByte(offset);
        final int locate = buf.getShort(offset + 1, BE) & 0xFFFF;
        final long ts = timestamp(buf, offset + 5);

        switch (type) {
            case ADD_ORDER, ADD_ORDER_MPID -> listener.onAddOrder(
                    locate, ts,
                    buf.getLong(offset + 11, BE),
                    buf.getByte(offset + 19),
                    buf.getInt(offset + 20, BE),
                    buf.getLong(offset + 24, BE),
                    buf.getInt(offset + 32, BE));
            case ORDER_EXECUTED -> listener.onOrderExecuted(
                    locate, ts,
                    buf.getLong(offset + 11, BE),
                    buf.getInt(offset + 19, BE),
                    buf.getLong(offset + 23, BE));
            case ORDER_EXECUTED_WITH_PRICE -> listener.onOrderExecutedWithPrice(
                    locate, ts,
                    buf.getLong(offset + 11, BE),
                    buf.getInt(offset + 19, BE),
                    buf.getLong(offset + 23, BE),
                    buf.getByte(offset + 31),
                    buf.getInt(offset + 32, BE));
            case ORDER_CANCEL -> listener.onOrderCancel(
                    locate, ts,
                    buf.getLong(offset + 11, BE),
                    buf.getInt(offset + 19, BE));
            case ORDER_DELETE -> listener.onOrderDelete(
                    locate, ts,
                    buf.getLong(offset + 11, BE));
            case ORDER_REPLACE -> listener.onOrderReplace(
                    locate, ts,
                    buf.getLong(offset + 11, BE),
                    buf.getLong(offset + 19, BE),
                    buf.getInt(offset + 27, BE),
                    buf.getInt(offset + 31, BE));
            case TRADE -> listener.onTrade(
                    locate, ts,
                    buf.getLong(offset + 11, BE),
                    buf.getByte(offset + 19),
                    buf.getInt(offset + 20, BE),
                    buf.getLong(offset + 24, BE),
                    buf.getInt(offset + 32, BE),
                    buf.getLong(offset + 36, BE));
            case SYSTEM_EVENT -> listener.onSystemEvent(
                    locate, ts,
                    buf.getByte(offset + 11));
            case STOCK_DIRECTORY -> listener.onStockDirectory(
                    locate, ts,
                    buf.getLong(offset + 11, BE));
            default -> listener.onOther(type, locate, ts);
        }
        return type;
    }

    /** ITCH timestamps are 48-bit big-endian nanoseconds since midnight. */
    private static long timestamp(DirectBuffer buf, int offset) {
        final long high = buf.getShort(offset, BE) & 0xFFFFL;
        final long low = buf.getInt(offset + 2, BE) & 0xFFFF_FFFFL;
        return (high << 32) | low;
    }
}
