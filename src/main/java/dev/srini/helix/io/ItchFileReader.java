package dev.srini.helix.io;

import dev.srini.helix.itch.ItchParser;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads a length-framed ITCH 5.0 capture via a memory-mapped file and drives an
 * {@link ItchParser} over it with no per-message allocation.
 *
 * <p>Frame format matches the NASDAQ BinaryFILE convention: each message is
 * preceded by a 2-byte big-endian length. The whole file is mapped once and the
 * parser reads directly out of the mapping, so throughput is bounded by decode
 * and book-update cost rather than syscalls.
 *
 * <p>Files larger than 2 GiB are mapped in windows; this reader maps the entire
 * file, which is sufficient for the multi-hundred-million-message single-day
 * captures the project targets on a 64-bit JVM.
 */
public final class ItchFileReader implements Closeable {

    private final FileChannel channel;
    private final MappedByteBuffer mapped;
    private final UnsafeBuffer buffer;
    private final long size;

    public ItchFileReader(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.size = channel.size();
        this.mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
        this.mapped.order(ByteOrder.BIG_ENDIAN);
        this.buffer = new UnsafeBuffer(mapped);
    }

    /**
     * Drive the parser over every framed message in the file.
     *
     * @return number of messages decoded
     */
    public long replay(ItchParser parser) {
        long count = 0;
        int offset = 0;
        final long limit = size;
        while (offset + 2 <= limit) {
            final int len = buffer.getShort(offset, ByteOrder.BIG_ENDIAN) & 0xFFFF;
            if (len == 0 || offset + 2 + len > limit) {
                break; // trailing padding or truncated final frame
            }
            parser.parse(buffer, offset + 2);
            offset += 2 + len;
            count++;
        }
        return count;
    }

    public long sizeBytes() {
        return size;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
