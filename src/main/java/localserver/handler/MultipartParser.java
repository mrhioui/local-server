package localserver.handler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses multipart/form-data request bodies into their individual parts.
 *
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
     *
     * @param body     raw request body bytes
     * @param boundary boundary token (without leading "--")
     * @return list of parsed parts; empty if none found
     */
    public static List<Part> parse(byte[] body, String boundary) {
        List<Part> parts = new ArrayList<>();
        if (body == null || boundary == null || boundary.isEmpty()) return parts;

        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] CRLF      = "\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] CRLFCRLF  = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        int pos = indexOf(body, delimiter, 0);
        if (pos < 0) return parts;

        while (true) {
            // skip past delimiter + CRLF
            pos += delimiter.length;
            if (pos + 1 >= body.length) break;
            // "--boundary--" → final boundary
            if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') break;
            // skip CRLF after boundary
            if (startsWith(body, pos, CRLF)) pos += 2;

            // find end of headers (double CRLF)
            int headersEnd = indexOf(body, CRLFCRLF, pos);
            if (headersEnd < 0) break;

            String rawHeaders = new String(body, pos, headersEnd - pos, StandardCharsets.UTF_8);
            pos = headersEnd + CRLFCRLF.length;

            // find next boundary
            byte[] nextBoundary = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
            int contentEnd = indexOf(body, nextBoundary, pos);
            if (contentEnd < 0) contentEnd = body.length;

            byte[] content = new byte[contentEnd - pos];
            System.arraycopy(body, pos, content, 0, content.length);

            Part part = parsePart(rawHeaders, content);
            if (part != null) parts.add(part);

            // advance to next boundary
            pos = contentEnd + 2; // skip \r\n
            int nextBound = indexOf(body, delimiter, pos);
            if (nextBound < 0) break;
            pos = nextBound;
        }

        return parts;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Part parsePart(String rawHeaders, byte[] content) {
        Part part = new Part();
        part.content = content;
        part.contentType = "application/octet-stream";

        for (String line : rawHeaders.split("\r\n")) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("content-disposition:")) {
                String value = line.substring(line.indexOf(':') + 1).trim();
                part.name     = extractParam(value, "name");
                part.filename = extractParam(value, "filename");
            } else if (lower.startsWith("content-type:")) {
                part.contentType = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return part;
    }

    /** Extract a quoted parameter value like name="foo" from a header value. */
    private static String extractParam(String header, String param) {
        String search = param + "=";
        int idx = header.toLowerCase(java.util.Locale.ROOT).indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        if (idx >= header.length()) return null;
        if (header.charAt(idx) == '"') {
            int end = header.indexOf('"', idx + 1);
            return end > idx ? header.substring(idx + 1, end) : null;
        }
        // unquoted
        int end = header.indexOf(';', idx);
        return (end < 0 ? header.substring(idx) : header.substring(idx, end)).trim();
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (offset + prefix.length > data.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) return false;
        }
        return true;
    }
}
