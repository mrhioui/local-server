package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static String bodyOf(Response r) {
        return new String(r.getBody(), StandardCharsets.UTF_8);
    }

    private static ServerConfig configWithErrorPages(Map<Integer, Path> errorPages) {
        return new ServerConfig(0, "127.0.0.1", List.of(8080), List.of(), true, 1024,
                errorPages, List.of());
    }

    // ── generated (default) pages ────────────────────────────────────────────

    @Test
    void defaultErrorPage() {
        Response resp = ErrorPages.render(404);
        assertEquals(404, resp.getStatusCode());
        assertEquals("text/html; charset=utf-8", resp.getHeaders().get("content-type"));
        assertTrue(bodyOf(resp).contains("404 Not Found"));
    }

    @Test
    void render500HasCorrectStatus() {
        Response r = ErrorPages.render(500);
        assertEquals(500, r.getStatusCode());
    }

    @Test
    void bodyContainsStatusCodeAndReason() {
        Response r = ErrorPages.render(403);
        String body = bodyOf(r);
        assertTrue(body.contains("403"), "Body should mention status code");
        assertTrue(body.contains("Forbidden"), "Body should mention reason phrase");
    }

    @Test
    void bodyIsHtml() {
        Response r = ErrorPages.render(400);
        String body = bodyOf(r);
        assertTrue(body.contains("<html>") || body.contains("<!DOCTYPE"), "Body should be HTML");
    }

    @Test
    void nullConfigRendersGenericPage() {
        Response r = ErrorPages.render(500, null);
        assertEquals(500, r.getStatusCode());
        assertNotNull(r.getBody());
        assertTrue(r.getBody().length > 0);
        assertTrue(bodyOf(r).contains("500 Internal Server Error"));
    }

    // ── custom pages from ServerConfig ───────────────────────────────────────

    @Test
    void customErrorPageLoadedWhenPresent(@TempDir Path tempDir) throws IOException {
        Path custom404 = tempDir.resolve("my404.html");
        Files.writeString(custom404, "<h1>Custom 404 Page</h1>");

        Response resp = ErrorPages.render(404, configWithErrorPages(Map.of(404, custom404)));

        assertEquals(404, resp.getStatusCode());
        assertEquals("text/html; charset=utf-8", resp.getHeaders().get("content-type"));
        assertEquals("<h1>Custom 404 Page</h1>", bodyOf(resp));
    }

    @Test
    void fallsBackToDefaultIfCustomPageNotConfigured() {
        Response resp = ErrorPages.render(403, configWithErrorPages(Map.of()));

        assertEquals(403, resp.getStatusCode());
        assertTrue(bodyOf(resp).contains("403 Forbidden"));
    }

    @Test
    void missingCustomFileFallsBackToDefault(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.html");

        Response resp = ErrorPages.render(404, configWithErrorPages(Map.of(404, missing)));

        assertEquals(404, resp.getStatusCode());
        assertTrue(bodyOf(resp).contains("404 Not Found"));
    }

    @Test
    void customPageThatIsADirectoryFallsBackToDefault(@TempDir Path tempDir) {
        Response resp = ErrorPages.render(404, configWithErrorPages(Map.of(404, tempDir)));

        assertEquals(404, resp.getStatusCode());
        assertTrue(bodyOf(resp).contains("404 Not Found"));
    }

    @Test
    void customErrorPagePreloadedAndServedFromCache(@TempDir Path tempDir) throws IOException {
        Path custom500 = tempDir.resolve("500.html");
        Files.writeString(custom500, "<h1>Preloaded 500</h1>");

        ServerConfig cfg = configWithErrorPages(Map.of(500, custom500));
        ErrorPages.preload(cfg);

        // Delete the file on disk to prove it is served from in-memory cache
        Files.delete(custom500);

        Response resp = ErrorPages.render(500, cfg);
        assertEquals(500, resp.getStatusCode());
        assertEquals("<h1>Preloaded 500</h1>", bodyOf(resp));
    }
}