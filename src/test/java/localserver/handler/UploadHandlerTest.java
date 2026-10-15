package localserver.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import localserver.config.Route;
import localserver.http.Headers;
import localserver.http.MemoryBodySink;
import localserver.http.Request;
import localserver.http.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class UploadHandlerTest {

    @TempDir
    Path tempDir;

    private MemoryBodySink createSink(byte[] bytes) throws IOException {
        MemoryBodySink sink = new MemoryBodySink();
        sink.write(ByteBuffer.wrap(bytes), bytes.length);
        return sink;
    }

    private Route createRoute(String path, Path root, Map<String, String> cgi) {
        return new Route(
                path,
                Set.of("GET", "POST", "DELETE"),
                root,
                null,
                false,
                null,
                cgi != null ? cgi : Collections.emptyMap()
        );
    }

    @Test
    void rawUploadSucceeds() throws IOException {
        Route route = createRoute("/upload", tempDir, null);
        byte[] payload = "Hello raw upload".getBytes(StandardCharsets.UTF_8);
        MemoryBodySink sink = createSink(payload);

        Headers headers = new Headers();
        headers.add("Content-Type", "text/plain");
        Request request = Request.of("POST", "/upload/test.txt", "localhost", headers, sink);

        UploadHandler handler = new UploadHandler();
        Response res = handler.handle(request, route);

        assertEquals(201, res.getStatusCode());
        assertEquals("/upload/test.txt", res.getHeaders().get("Location"));
        Path uploaded = tempDir.resolve("test.txt");
        assertTrue(Files.exists(uploaded));
        assertEquals("Hello raw upload", Files.readString(uploaded));
    }

    @Test
    void multipartUploadSucceeds() throws IOException {
        Route route = createRoute("/upload", tempDir, null);
        String boundary = "testBound";
        String bodyStr = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "Multipart payload content\r\n"
                + "--" + boundary + "--\r\n";
        byte[] payload = bodyStr.getBytes(StandardCharsets.UTF_8);
        MemoryBodySink sink = createSink(payload);

        Headers headers = new Headers();
        headers.add("Content-Type", "multipart/form-data; boundary=" + boundary);
        Request request = Request.of("POST", "/upload", "localhost", headers, sink);

        UploadHandler handler = new UploadHandler();
        Response res = handler.handle(request, route);

        assertEquals(201, res.getStatusCode());
        Path uploaded = tempDir.resolve("hello.txt");
        assertTrue(Files.exists(uploaded));
        assertEquals("Multipart payload content", Files.readString(uploaded));
    }

    @Test
    void rejectsCgiScriptUpload() throws IOException {
        Route route = createRoute("/upload", tempDir, Map.of(".py", "/usr/bin/python3"));
        byte[] payload = "import os; print('hi')".getBytes(StandardCharsets.UTF_8);
        MemoryBodySink sink = createSink(payload);

        Headers headers = new Headers();
        headers.add("Content-Type", "text/plain");
        Request request = Request.of("POST", "/upload/bad.py", "localhost", headers, sink);

        UploadHandler handler = new UploadHandler();
        Response res = handler.handle(request, route);

        assertEquals(403, res.getStatusCode());
        assertFalse(Files.exists(tempDir.resolve("bad.py")));
    }

    @Test
    void rejectsHiddenFilenameUpload() throws IOException {
        Route route = createRoute("/upload", tempDir, null);
        byte[] payload = "hidden file".getBytes(StandardCharsets.UTF_8);
        MemoryBodySink sink = createSink(payload);

        Headers headers = new Headers();
        headers.add("Content-Type", "text/plain");
        Request request = Request.of("POST", "/upload/.secret", "localhost", headers, sink);

        UploadHandler handler = new UploadHandler();
        Response res = handler.handle(request, route);

        assertEquals(403, res.getStatusCode());
        assertFalse(Files.exists(tempDir.resolve(".secret")));
    }

    @Test
    void rejectsEmptyBody() {
        Route route = createRoute("/upload", tempDir, null);
        Request request = Request.of("POST", "/upload/file.txt", "localhost");

        UploadHandler handler = new UploadHandler();
        Response res = handler.handle(request, route);

        assertEquals(400, res.getStatusCode());
    }
}
