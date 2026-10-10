package localserver.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

/** Keeps the body in a byte array. Only for small bodies. */
public final class MemoryBodySink implements BodySink {
    private byte[] data = new byte[256];
    private int size;

    @Override
    public void write(ByteBuffer src, int length) throws IOException {
        if ((long) size + length > Integer.MAX_VALUE - 8) {
            throw new IOException("body too large for memory");
        }
        if (size + length > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, size + length));
        }
        src.get(data, size, length);
        size += length;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public InputStream openRead() {
        return new ByteArrayInputStream(data, 0, size);
    }

    @Override
    public Path file() {
        return null;
    }

    @Override
    public void close() {
        data = new byte[0];
        size = 0;
    }
}
