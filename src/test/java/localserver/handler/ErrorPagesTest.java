package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import localserver.config.ServerConfig;
import localserver.http.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorPagesTest {

    @Test
    void defaultErrorPage() {
        Response resp = ErrorPages.render(404);
        assertEquals(404, resp.getStatusCode());
        assertEquals("text/html; charset=utf-8", resp.getHeaders().get("content-type"));
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("404 Not Found"));
    }

    @Test
    void customErrorPageLoadedWhenPresent(@TempDir Path tempDir) throws IOException {
        Path custom404 = tempDir.resolve("my404.html");
        Files.writeString(custom404, "<h1>Custom 404 Page</h1>");

        ServerConfig config = new ServerConfig(0, "127.0.0.1", List.of(8080), List.of(), true, 1024,
                Map.of(404, custom404), List.of());

        Response resp = ErrorPages.render(404, config);
        assertEquals(404, resp.getStatusCode());
        assertEquals("text/html; charset=utf-8", resp.getHeaders().get("content-type"));
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertEquals("<h1>Custom 404 Page</h1>", body);
    }

    @Test
    void fallsBackToDefaultIfCustomPageNotConfigured(@TempDir Path tempDir) {
        ServerConfig config = new ServerConfig(0, "127.0.0.1", List.of(8080), List.of(), true, 1024,
                Map.of(), List.of());

        Response resp = ErrorPages.render(403, config);
        assertEquals(403, resp.getStatusCode());
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("403 Forbidden"));
    }
}
