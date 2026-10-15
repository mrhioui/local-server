package localserver.handler;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultipartParserTest {

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void parseSingleTextPart() {
        String boundary = "abc123";
        String raw =
                "--abc123\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n" +
                "\r\n" +
                "hello world\r\n" +
                "--abc123--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), boundary);
        assertEquals(1, parts.size());
        assertEquals("field1", parts.get(0).name);
        assertNull(parts.get(0).filename);
        assertArrayEquals("hello world".getBytes(StandardCharsets.US_ASCII), parts.get(0).content);
    }

    @Test
    void parseFileUploadPart() {
        String boundary = "xyz";
        String raw =
                "--xyz\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "file content here\r\n" +
                "--xyz--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), boundary);
        assertEquals(1, parts.size());
        assertEquals("file", parts.get(0).name);
        assertEquals("test.txt", parts.get(0).filename);
        assertEquals("text/plain", parts.get(0).contentType);
    }

    @Test
    void parseMultipleParts() {
        String boundary = "sep";
        String raw =
                "--sep\r\n" +
                "Content-Disposition: form-data; name=\"a\"\r\n" +
                "\r\n" +
                "aaa\r\n" +
                "--sep\r\n" +
                "Content-Disposition: form-data; name=\"b\"\r\n" +
                "\r\n" +
                "bbb\r\n" +
                "--sep--\r\n";

        List<MultipartParser.Part> parts = MultipartParser.parse(body(raw), boundary);
        assertEquals(2, parts.size());
        assertEquals("a", parts.get(0).name);
        assertEquals("b", parts.get(1).name);
    }

    @Test
    void emptyBodyReturnsEmptyList() {
        List<MultipartParser.Part> parts = MultipartParser.parse(new byte[0], "boundary");
        assertTrue(parts.isEmpty());
    }

    @Test
    void nullBodyReturnsEmptyList() {
        List<MultipartParser.Part> parts = MultipartParser.parse(null, "boundary");
        assertTrue(parts.isEmpty());
    }

    @Test
    void nullBoundaryReturnsEmptyList() {
        List<MultipartParser.Part> parts = MultipartParser.parse(body("data"), null);
        assertTrue(parts.isEmpty());
    }
}

