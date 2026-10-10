package localserver.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BodySinkTest {

    @TempDir
    Path dir;

    private static byte[] readAll(BodySink sink) throws IOException {
        try (InputStream in = sink.openRead()) {
            return in.readAllBytes();
        }
    }

    private static void exerciseSink(BodySink sink) throws IOException {
        byte[] data = new byte[100_000];
        new Random(7).nextBytes(data);
        ByteBuffer src = ByteBuffer.wrap(data);
        // write in uneven pieces and check that exactly the requested bytes are consumed each time
        int[] pieces = {1, 10, 8192, 5, 50_000, 41_792};
        int written = 0;
        for (int n : pieces) {
            sink.write(src, n);
            written += n;
            assertEquals(written, src.position(), "position advances by exactly the length written");
            assertEquals(written, sink.size());
        }
        assertEquals(data.length, written);
        assertArrayEquals(data, readAll(sink));
    }

    @Test
    void memorySinkStoresBytesExactly() throws IOException {
        MemoryBodySink sink = new MemoryBodySink();
        exerciseSink(sink);
        assertNull(sink.file());
        sink.close();
        sink.close();
    }

    @Test
    void fileSinkStoresBytesExactlyAndDeletesItsFileOnClose() throws IOException {
        FileBodySink sink = FileBodySink.create(dir);
        Path file = sink.file();
        assertNotNull(file);
        assertTrue(Files.exists(file));
        assertEquals(dir, file.getParent());
        exerciseSink(sink);
        sink.close();
        assertFalse(Files.exists(file), "the temp file is removed on close");
        sink.close();                                         // idempotent
        assertThrows(IOException.class, () -> sink.write(ByteBuffer.wrap(new byte[1]), 1));
    }

    @Test
    void fileSinkWritesOnlyTheRequestedPartOfTheBuffer() throws IOException {
        FileBodySink sink = FileBodySink.create(dir);
        ByteBuffer src = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
        src.position(1);
        sink.write(src, 3);
        assertEquals(4, src.position());
        assertArrayEquals(new byte[] {2, 3, 4}, readAll(sink));
        sink.close();
    }

    @Test
    void anEmptySinkReadsAsEmpty() throws IOException {
        assertEquals(0, readAll(new MemoryBodySink()).length);
        FileBodySink f = FileBodySink.create(dir);
        assertEquals(0, readAll(f).length);
        f.close();
    }
}
