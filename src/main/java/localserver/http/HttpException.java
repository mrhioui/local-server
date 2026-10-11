package localserver.http;

/** A request problem that maps to an HTTP status (400 malformed, 413 too large, ...). */
public final class HttpException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
