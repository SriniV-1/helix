package dev.srini.helix.itch;

import dev.srini.helix.io.ItchFileReader;
import dev.srini.helix.io.ItchWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that every field written by {@link ItchWriter} is decoded identically
 * by {@link ItchParser}, i.e. the wire format is symmetric.
 */
class ParserRoundTripTest {

    @Test
    void writtenFieldsDecodeIdentically(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("rt.itch");
        try (ItchWriter w = new ItchWriter(file)) {
            w.addOrder(7, 123_456_789L, 1001L, (byte) 'B', 500, 0x53594D3030303031L, 145_2500);
            w.orderExecuted(7, 123_456_800L, 1001L, 200, 9L);
            w.orderCancel(7, 123_456_810L, 1001L, 100);
            w.orderReplace(7, 123_456_820L, 1001L, 2002L, 300, 145_5000);
            w.orderDelete(7, 123_456_830L, 2002L);
        }

        final List<String> decoded = new ArrayList<>();
        final ItchParser parser = new ItchParser(new RecordingListener(decoded));
        try (ItchFileReader reader = new ItchFileReader(file)) {
            reader.replay(parser);
        }

        assertEquals(5, decoded.size());
        assertEquals("add ref=1001 side=66 shares=500 price=1452500 ts=123456789", decoded.get(0));
        assertEquals("exec ref=1001 shares=200 match=9", decoded.get(1));
        assertEquals("cancel ref=1001 shares=100", decoded.get(2));
        assertEquals("replace old=1001 new=2002 shares=300 price=1455000", decoded.get(3));
        assertEquals("delete ref=2002", decoded.get(4));
    }

    private record RecordingListener(List<String> out) implements ItchListener {
        @Override
        public void onSystemEvent(int l, long t, byte c) {
        }

        @Override
        public void onStockDirectory(int l, long t, long s) {
        }

        @Override
        public void onAddOrder(int l, long t, long ref, byte side, int shares, long sym, int price) {
            out.add("add ref=" + ref + " side=" + side + " shares=" + shares + " price=" + price + " ts=" + t);
        }

        @Override
        public void onOrderExecuted(int l, long t, long ref, int shares, long match) {
            out.add("exec ref=" + ref + " shares=" + shares + " match=" + match);
        }

        @Override
        public void onOrderExecutedWithPrice(int l, long t, long ref, int shares, long match, byte p, int price) {
            out.add("execp ref=" + ref + " shares=" + shares + " match=" + match + " price=" + price);
        }

        @Override
        public void onOrderCancel(int l, long t, long ref, int shares) {
            out.add("cancel ref=" + ref + " shares=" + shares);
        }

        @Override
        public void onOrderDelete(int l, long t, long ref) {
            out.add("delete ref=" + ref);
        }

        @Override
        public void onOrderReplace(int l, long t, long oldRef, long newRef, int shares, int price) {
            out.add("replace old=" + oldRef + " new=" + newRef + " shares=" + shares + " price=" + price);
        }

        @Override
        public void onTrade(int l, long t, long ref, byte side, int shares, long sym, int price, long match) {
            out.add("trade ref=" + ref);
        }
    }
}
