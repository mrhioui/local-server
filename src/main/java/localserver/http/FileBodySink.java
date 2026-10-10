package localserver.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streams the body into a temp file. Writes are blocking but small (at most one socket read
 * buffer each), which is the price of staying on java.nio without a thread pool.
 */
public final class FileBodySink implements BodySink {
    private final Path path;
    private FileChannel channel;
    private long size;

    private FileBodySink(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    /** Creates an empty temp file inside {@code dir}. */
    public static FileBodySink create(Path dir) throws IOException {
        Path file = Files.createTempFile(dir, "body-", ".tmp");
        try {
            return new FileBodySink(file, FileChannel.open(file, StandardOpenOption.WRITE));
        } catch (IOException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    @Override
    public void write(ByteBuffer src, int length) throws IOException {
        if (channel == null) {
            throw new IOException("sink is closed");
        }
        ByteBuffer slice = src.slice();
        slice.limit(length);
        while (slice.hasRemaining()) {
            channel.write(slice);
        }
        src.position(src.position() + length);
        size += length;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public InputStream openRead() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public Path file() {
        return path;
    }

    @Override
    public void close() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            channel = null;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort; the server also removes its temp directory on exit
        }
    }
}
