package dev.srini.helix.io;

import org.agrona.concurrent.UnsafeBuffer;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes length-framed ITCH 5.0 messages in the same format {@link ItchFileReader}
 * consumes. Used by the synthetic capture generator and by tests that need a
 * byte-exact round trip.
 */
public final class ItchWriter implements Closeable {

    private final OutputStream out;
    private final byte[] frame = new byte[64];
    private final UnsafeBuffer buf = new UnsafeBuffer(frame);
    private long bytesWritten;

    public ItchWriter(Path path) throws IOException {
        this.out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
    }

    public void addOrder(int locate, long ts, long ref, byte side, int shares,
                         long symbol, int priceE4) throws IOException {
        buf.putByte(0, (byte) 'A');
        putHeader(locate, ts);
        buf.putLong(11, ref, ByteOrder.BIG_ENDIAN);
        buf.putByte(19, side);
        buf.putInt(20, shares, ByteOrder.BIG_ENDIAN);
        buf.putLong(24, symbol, ByteOrder.BIG_ENDIAN);
        buf.putInt(32, priceE4, ByteOrder.BIG_ENDIAN);
        writeFrame(36);
    }

    public void orderExecuted(int locate, long ts, long ref, int shares, long match) throws IOException {
        buf.putByte(0, (byte) 'E');
        putHeader(locate, ts);
        buf.putLong(11, ref, ByteOrder.BIG_ENDIAN);
        buf.putInt(19, shares, ByteOrder.BIG_ENDIAN);
        buf.putLong(23, match, ByteOrder.BIG_ENDIAN);
        writeFrame(31);
    }

    public void orderCancel(int locate, long ts, long ref, int shares) throws IOException {
        buf.putByte(0, (byte) 'X');
        putHeader(locate, ts);
        buf.putLong(11, ref, ByteOrder.BIG_ENDIAN);
        buf.putInt(19, shares, ByteOrder.BIG_ENDIAN);
        writeFrame(23);
    }

    public void orderDelete(int locate, long ts, long ref) throws IOException {
        buf.putByte(0, (byte) 'D');
        putHeader(locate, ts);
        buf.putLong(11, ref, ByteOrder.BIG_ENDIAN);
        writeFrame(19);
    }

    public void orderReplace(int locate, long ts, long oldRef, long newRef,
                             int shares, int priceE4) throws IOException {
        buf.putByte(0, (byte) 'U');
        putHeader(locate, ts);
        buf.putLong(11, oldRef, ByteOrder.BIG_ENDIAN);
        buf.putLong(19, newRef, ByteOrder.BIG_ENDIAN);
        buf.putInt(27, shares, ByteOrder.BIG_ENDIAN);
        buf.putInt(31, priceE4, ByteOrder.BIG_ENDIAN);
        writeFrame(35);
    }

    public void systemEvent(long ts, byte code) throws IOException {
        buf.putByte(0, (byte) 'S');
        putHeader(0, ts);
        buf.putByte(11, code);
        writeFrame(12);
    }

    private void putHeader(int locate, long ts) {
        buf.putShort(1, (short) locate, ByteOrder.BIG_ENDIAN);
        buf.putShort(3, (short) 0, ByteOrder.BIG_ENDIAN); // tracking number
        buf.putShort(5, (short) (ts >>> 32), ByteOrder.BIG_ENDIAN);
        buf.putInt(7, (int) ts, ByteOrder.BIG_ENDIAN);
    }

    private void writeFrame(int len) throws IOException {
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(frame, 0, len);
        bytesWritten += 2 + len;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
    }
}
