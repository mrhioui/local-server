package localserver.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Where a request body goes while it arrives. Small bodies can stay in memory; uploads and CGI
 * input go to a temp file so a big body never has to fit in RAM. Bodies are raw bytes: nothing
 * here ever turns them into a String.
 */
public interface BodySink extends AutoCloseable {

    /** Consumes exactly {@code length} bytes from {@code src}, advancing its position by that much. */
    void write(ByteBuffer src, int length) throws IOException;

    /** Number of body bytes stored so far. */
    long size();

    /** A fresh stream over the stored bytes. */
    InputStream openRead() throws IOException;

    /** The backing temp file, or null for an in-memory sink. */
    Path file();

    /** Releases memory or deletes the temp file. Safe to call more than once. */
    @Override
    void close();
}
