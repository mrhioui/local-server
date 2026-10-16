package localserver.handler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses multipart/form-data request bodies into their individual parts.
 *
 * NEW (person B). STUB.
 */
public final class MultipartParser {

    private static final Pattern DISPOSITION_NAME = Pattern.compile("name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPOSITION_FILENAME = Pattern.compile("filename=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

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
        if (body == null || boundary == null || boundary.isEmpty()) {
            return List.of();
        }

        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Integer> positions = findDelimiterPositions(body, delimiter);
        if (positions.size() < 2) {
            return List.of();
        }

        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < positions.size() - 1; i++) {
            int partStart = positions.get(i) + delimiter.length;
            int nextBoundary = positions.get(i + 1);

            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            } else if (partStart < body.length && body[partStart] == '\n') {
                partStart += 1;
            }

            int partEnd = nextBoundary;
            if (partEnd >= partStart + 2 && body[partEnd - 2] == '\r' && body[partEnd - 1] == '\n') {
                partEnd -= 2;
            } else if (partEnd >= partStart + 1 && body[partEnd - 1] == '\n') {
                partEnd -= 1;
            }

            if (partStart > partEnd) {
                continue;
            }

            int headerEnd = findHeaderEnd(body, partStart, partEnd);
            if (headerEnd == -1) {
                continue;
            }

            String headerText = new String(body, partStart, headerEnd - partStart, StandardCharsets.ISO_8859_1);
            int bodyStart = headerEnd + 4;
            if (body[headerEnd] == '\n') {
                bodyStart = headerEnd + 2;
            }

            byte[] partContent = (bodyStart <= partEnd) ? Arrays.copyOfRange(body, bodyStart, partEnd) : new byte[0];
            Part part = new Part();
            part.content = partContent;

            for (String line : headerText.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String headerName = line.substring(0, colon).trim().toLowerCase();
                String headerValue = line.substring(colon + 1).trim();

                if (headerName.equals("content-disposition")) {
                    Matcher mName = DISPOSITION_NAME.matcher(headerValue);
                    if (mName.find()) {
                        part.name = mName.group(1);
                    }
                    Matcher mFile = DISPOSITION_FILENAME.matcher(headerValue);
                    if (mFile.find()) {
                        part.filename = mFile.group(1);
                    }
                } else if (headerName.equals("content-type")) {
                    part.contentType = headerValue;
                }
            }

            parts.add(part);
        }

        return parts;
    }

    private static List<Integer> findDelimiterPositions(byte[] data, byte[] delimiter) {
        List<Integer> list = new ArrayList<>();
        int limit = data.length - delimiter.length;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < delimiter.length; j++) {
                if (data[i + j] != delimiter[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                list.add(i);
                i += delimiter.length - 1;
            }
        }
        return list;
    }

    private static int findHeaderEnd(byte[] data, int start, int end) {
        for (int i = start; i <= end - 4; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        for (int i = start; i <= end - 2; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
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
