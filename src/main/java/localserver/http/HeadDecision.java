package localserver.http;

import java.io.IOException;

/** The answer to {@link HeadListener#onHead}: read the body under these rules, or refuse now. */
public final class HeadDecision {

    /** Creates the sink lazily, so a request that is refused never allocates a temp file. */
    @FunctionalInterface
    public interface SinkFactory {
        BodySink create() throws IOException;
    }

    private final int status;
    private final String message;
    private final long maxBodyBytes;
    private final SinkFactory sinkFactory;

    private HeadDecision(int status, String message, long maxBodyBytes, SinkFactory sinkFactory) {
        this.status = status;
        this.message = message;
        this.maxBodyBytes = maxBodyBytes;
        this.sinkFactory = sinkFactory;
    }

    /** Read the body (if any) into a sink from {@code factory}, refusing more than {@code maxBodyBytes}. */
    public static HeadDecision accept(long maxBodyBytes, SinkFactory factory) {
        return new HeadDecision(0, null, maxBodyBytes, factory);
    }

    /** Refuse the request before reading its body. */
    public static HeadDecision reject(int status, String message) {
        return new HeadDecision(status, message, 0, null);
    }

    public boolean isRejected() {
        return status != 0;
    }

    public int status() {
        return status;
    }

    public String message() {
        return message;
    }

    public long maxBodyBytes() {
        return maxBodyBytes;
    }

    public SinkFactory sinkFactory() {
        return sinkFactory;
    }
}
