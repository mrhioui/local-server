package localserver.handler;

import java.util.List;

/**
 * Parses multipart/form-data request bodies into their individual parts.
 *
 * NEW (person B). STUB.
 */
public final class MultipartParser {

    private MultipartParser() {
    }

    /** One parsed part of a multipart body. */
    public static final class Part {
        public String name;
        public String filename;
        public String contentType;
        public byte[] content;
    }

    /**
     * Parse a multipart body given its boundary (from the Content-Type header).
     */
    public static List<Part> parse(byte[] body, String boundary) {
        throw new UnsupportedOperationException("TODO: implement MultipartParser.parse");
    }
}
