package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultipartParserTest {

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── well-formed bodies ───────────────────────────────────────────────────

    @Test
    void parsesSinglePart() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String raw = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "Hello World\r\n"
                + "--" + boundary + "--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), boundary);
        assertEquals(1, parts.size());
        assertEquals("title", parts.get(0).name);
        assertNull(parts.get(0).filename);
        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), parts.get(0).content);
    }

    @Test
    void parsesFilePartWithHeaders() {
        String boundary = "xyzBoundary";
        String raw = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"upload\"; filename=\"sample.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "File content here!\r\n"
                + "--" + boundary + "--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), boundary);
        assertEquals(1, parts.size());
        assertEquals("upload", parts.get(0).name);
        assertEquals("sample.txt", parts.get(0).filename);
        assertEquals("text/plain", parts.get(0).contentType);
        assertEquals("File content here!", text(parts.get(0).content));
    }

    @Test
    void parsesMultipleParts() {
        String boundary = "abc";
        String raw = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
                + "val1\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + "binary-data\r\n"
                + "--" + boundary + "--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), boundary);
        assertEquals(2, parts.size());
        assertEquals("field1", parts.get(0).name);
        assertEquals("val1", text(parts.get(0).content));
        assertEquals("file", parts.get(1).name);
        assertEquals("a.bin", parts.get(1).filename);
        assertEquals("binary-data", text(parts.get(1).content));
    }

    // ── empty / invalid input ────────────────────────────────────────────────

    @Test
    void returnsEmptyOnMalformedOrMissingBoundary() {
        assertEquals(0, MultipartParser.parse(new byte[0], "boundary").size());
        assertEquals(0, MultipartParser.parse(body("hello"), null).size());
        assertEquals(0, MultipartParser.parse(body("hello"), "").size());
        assertEquals(0, MultipartParser.parse(body("hello"), "missing").size());
    }

    @Test
    void nullBodyReturnsEmptyList() {
        List<MultipartParser.Part> parts = MultipartParser.parse(null, "boundary");
        assertTrue(parts.isEmpty());
    }

    // ── edge cases covered by the merged parser ──────────────────────────────

    @Test
    void missingContentTypeDefaultsToOctetStream() {
        String raw = "--abc\r\n"
                + "Content-Disposition: form-data; name=\"f\"; filename=\"x.bin\"\r\n\r\n"
                + "data\r\n"
                + "--abc--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), "abc");
        assertEquals(1, parts.size());
        assertEquals("application/octet-stream", parts.get(0).contentType);
    }

    @Test
    void boundaryTextInsideContentDoesNotSplitPart() {
        String raw = "--abc\r\n"
                + "Content-Disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\n\r\n"
                + "before --abc after\r\n"
                + "--abc--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), "abc");
        assertEquals(1, parts.size());
        assertEquals("before --abc after", text(parts.get(0).content));
    }

    @Test
    void contentContainingBlankLinesIsKeptIntact() {
        String raw = "--abc\r\n"
                + "Content-Disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\n\r\n"
                + "a\r\n\r\nb\r\n"
                + "--abc--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), "abc");
        assertEquals(1, parts.size());
        assertEquals("a\r\n\r\nb", text(parts.get(0).content));
    }

    @Test
    void acceptsBareLineFeedLineEndings() {
        String raw = "--abc\n"
                + "Content-Disposition: form-data; name=\"a\"\n\n"
                + "value\n"
                + "--abc--\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), "abc");
        assertEquals(1, parts.size());
        assertEquals("a", parts.get(0).name);
        assertEquals("value", text(parts.get(0).content));
    }

    @Test
    void decodesUtf8Filename() {
        String raw = "--abc\r\n"
                + "Content-Disposition: form-data; name=\"f\"; filename=\"café.txt\"\r\n\r\n"
                + "x\r\n"
                + "--abc--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), "abc");
        assertEquals(1, parts.size());
        assertEquals("café.txt", parts.get(0).filename);
    }
}