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
import localserver.server.CgiJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CgiHandlerTest {

    @TempDir
    Path tempDir;

    private Route createRoute(String path, Path root, Map<String, String> cgi) {
        return new Route(
                path,
                Set.of("GET", "POST"),
                root,
                null,
                false,
                null,
                cgi != null ? cgi : Collections.emptyMap()
        );
    }

    private MemoryBodySink createSink(byte[] bytes) throws IOException {
        MemoryBodySink sink = new MemoryBodySink();
        sink.write(ByteBuffer.wrap(bytes), bytes.length);
        return sink;
    }

    @Test
    void executesPythonScriptSuccessfully() throws IOException {
        Path script = tempDir.resolve("test.py");
        Files.writeString(script, """
                print("Content-Type: text/plain\\r\\n\\r\\nHello from Python CGI!")
                """);

        Route route = createRoute("/cgi-bin", tempDir, Map.of(".py", "/usr/bin/python3"));
        Request request = Request.of("GET", "/cgi-bin/test.py", "localhost");

        CgiHandler handler = new CgiHandler(tempDir, 5_000_000_000L);
        Response response = handler.handle(request, route);

        assertEquals(200, response.getStatusCode());
        assertEquals("text/plain", response.getHeaders().get("Content-Type"));
        assertEquals("Hello from Python CGI!\n", new String(response.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void parsesCustomStatusAndHeaders() throws IOException {
        Path script = tempDir.resolve("status.py");
        Files.writeString(script, """
                print("Status: 201 Created")
                print("Set-Cookie: user=alice; Path=/")
                print("X-Custom: LocalServer")
                print("Content-Type: text/plain")
                print("")
                print("Object Created")
                """);

        Route route = createRoute("/cgi-bin", tempDir, Map.of(".py", "/usr/bin/python3"));
        Request request = Request.of("GET", "/cgi-bin/status.py", "localhost");

        CgiHandler handler = new CgiHandler(tempDir, 5_000_000_000L);
        Response response = handler.handle(request, route);

        assertEquals(201, response.getStatusCode());
        assertEquals("LocalServer", response.getHeaders().get("X-Custom"));
        assertEquals("Object Created\n", new String(response.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void passesCgiEnvironmentAndStdin() throws IOException {
        Path script = tempDir.resolve("env_echo.py");
        Files.writeString(script, """
                import os, sys
                method = os.environ.get("REQUEST_METHOD", "")
                query = os.environ.get("QUERY_STRING", "")
                path_info = os.environ.get("PATH_INFO", "")
                auth = os.environ.get("HTTP_AUTHORIZATION", "")
                body = sys.stdin.read()
                print("Content-Type: text/plain\\r\\n\\r\\n" + f"{method}|{query}|{auth}|{body}|{path_info}")
                """);

        Route route = createRoute("/cgi-bin", tempDir, Map.of(".py", "/usr/bin/python3"));
        Headers headers = new Headers();
        headers.add("Authorization", "Bearer secret123");
        MemoryBodySink sink = createSink("client_payload".getBytes(StandardCharsets.UTF_8));

        Request request = Request.of("POST", "/cgi-bin/env_echo.py?a=1&b=2", "localhost", headers, sink);

        CgiHandler handler = new CgiHandler(tempDir, 5_000_000_000L);
        Response response = handler.handle(request, route);

        assertEquals(200, response.getStatusCode());
        String out = new String(response.getBody(), StandardCharsets.UTF_8);
        assertTrue(out.contains("POST|a=1&b=2|Bearer secret123|client_payload|" + script.toAbsolutePath()));
    }

    @Test
    void killsHangingProcessOnTimeout() throws IOException {
        Path script = tempDir.resolve("hang.py");
        Files.writeString(script, """
                import time
                time.sleep(10)
                print("Content-Type: text/plain\\r\\n\\r\\nDone")
                """);

        Route route = createRoute("/cgi-bin", tempDir, Map.of(".py", "/usr/bin/python3"));
        Request request = Request.of("GET", "/cgi-bin/hang.py", "localhost");

        // Set a small 200ms timeout
        CgiHandler handler = new CgiHandler(tempDir, 200_000_000L);
        Response response = handler.handle(request, route);

        assertEquals(504, response.getStatusCode());
    }

    @Test
    void parsesOutputWithoutStatusAs200() throws Exception {
        String output = "Content-Type: text/plain\r\n\r\nPlain text";
        Response res = CgiJob.parseOutput(output.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, res.getStatusCode());
        assertEquals("text/plain", res.getHeaders().get("Content-Type"));
        assertEquals("Plain text", new String(res.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void parsesLocationHeaderAs302ByDefault() throws Exception {
        String output = "Location: /new/path\r\n\r\n";
        Response res = CgiJob.parseOutput(output.getBytes(StandardCharsets.UTF_8));
        assertEquals(302, res.getStatusCode());
        assertEquals("/new/path", res.getHeaders().get("Location"));
    }
}
