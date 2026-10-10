package localserver.http;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Collects the bytes of one request head (request line + headers) until the blank line.
 *
 * <p>Incremental: feed it whatever a read produced, as often as needed. It stops consuming
 * right after the terminating CRLF CRLF, so any bytes that follow (a pipelined next request, or
 * the start of a body) stay in the caller's buffer. It never decodes anything; turning the bytes
 * into a request is the parser's job (M3), which embeds this class for its HEAD state.
 */
public final class HeadReader {

    public enum Result { NEED_MORE, COMPLETE, TOO_LARGE }

    private final int maxBytes;
    private byte[] buf = new byte[512];
    private int size;
    private int matched;      // how much of "\r\n\r\n" the most recent bytes match (0..4)
    private Result terminal;  // sticky once COMPLETE or TOO_LARGE, until reset()

    public HeadReader(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Consumes bytes from {@code in} (position advances) until the head ends or grows too large. */
    public Result feed(ByteBuffer in) {
        if (terminal != null) {
            return terminal;
        }
        while (in.hasRemaining()) {
            if (size >= maxBytes) {
                return terminal = Result.TOO_LARGE;
            }
            byte b = in.get();
            append(b);
            // Expecting '\r' at even positions and '\n' at odd ones; a stray '\r' restarts the match.
            if (b == (matched % 2 == 0 ? '\r' : '\n')) {
                matched++;
            } else {
                matched = b == '\r' ? 1 : 0;
            }
            if (matched == 4) {
                return terminal = Result.COMPLETE;
            }
        }
        return Result.NEED_MORE;
    }

    /** True once at least one byte of the current head has been received. */
    public boolean hasStarted() {
        return size > 0;
    }

    /** A copy of the head bytes collected so far (including the final blank line when complete). */
    public byte[] bytes() {
        return Arrays.copyOf(buf, size);
    }

    /** Prepares for the next request on the same connection. */
    public void reset() {
        size = 0;
        matched = 0;
        terminal = null;
        if (buf.length > 4096) {
            buf = new byte[512]; // do not let one big request pin memory for the connection's lifetime
        }
    }

    private void append(byte b) {
        if (size == buf.length) {
            buf = Arrays.copyOf(buf, Math.min(buf.length * 2, Math.max(maxBytes, 1)));
        }
        buf[size++] = b;
    }
}
