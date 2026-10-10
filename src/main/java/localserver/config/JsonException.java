package localserver.config;

/** Thrown when a JSON document is malformed. The message includes line and column. */
public final class JsonException extends Exception {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
