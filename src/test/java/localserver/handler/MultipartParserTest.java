package localserver.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

public class MultipartParserTest {

    @Test
    void parsesSinglePart() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String bodyStr = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "Hello World\r\n"
                + "--" + boundary + "--\r\n";
        byte[] body = bodyStr.getBytes(StandardCharsets.UTF_8);

        List<MultipartParser.Part> parts = MultipartParser.parse(body, boundary);
        assertEquals(1, parts.size());
        assertEquals("title", parts.get(0).name);
        assertNull(parts.get(0).filename);
        assertEquals("Hello World", new String(parts.get(0).content, StandardCharsets.UTF_8));
    }

    @Test
    void parsesFilePartWithHeaders() {
        String boundary = "xyzBoundary";
        String bodyStr = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"upload\"; filename=\"sample.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "File content here!\r\n"
                + "--" + boundary + "--\r\n";
        byte[] body = bodyStr.getBytes(StandardCharsets.UTF_8);

        List<MultipartParser.Part> parts = MultipartParser.parse(body, boundary);
        assertEquals(1, parts.size());
        assertEquals("upload", parts.get(0).name);
        assertEquals("sample.txt", parts.get(0).filename);
        assertEquals("text/plain", parts.get(0).contentType);
        assertEquals("File content here!", new String(parts.get(0).content, StandardCharsets.UTF_8));
    }

    @Test
    void parsesMultipleParts() {
        String boundary = "abc";
        String bodyStr = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
                + "val1\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + "binary-data\r\n"
                + "--" + boundary + "--\r\n";
        byte[] body = bodyStr.getBytes(StandardCharsets.UTF_8);

        List<MultipartParser.Part> parts = MultipartParser.parse(body, boundary);
        assertEquals(2, parts.size());
        assertEquals("val1", new String(parts.get(0).content, StandardCharsets.UTF_8));
        assertEquals("a.bin", parts.get(1).filename);
        assertEquals("binary-data", new String(parts.get(1).content, StandardCharsets.UTF_8));
    }

    @Test
    void returnsEmptyOnMalformedOrMissingBoundary() {
        assertEquals(0, MultipartParser.parse(new byte[0], "boundary").size());
        assertEquals(0, MultipartParser.parse("hello".getBytes(StandardCharsets.UTF_8), null).size());
        assertEquals(0, MultipartParser.parse("hello".getBytes(StandardCharsets.UTF_8), "").size());
        assertEquals(0, MultipartParser.parse("hello".getBytes(StandardCharsets.UTF_8), "missing").size());
    }
}
