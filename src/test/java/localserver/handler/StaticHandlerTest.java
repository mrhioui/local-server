package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticHandlerTest {

    @TempDir
    Path tempDir;

    private Path root;
    private StaticHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("site");
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.html"), "<h1>Home</h1>");
        Files.writeString(root.resolve("style.css"), "body { margin: 0; }");

        Path sub = root.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("data.txt"), "hello data");

        Path autoDir = root.resolve("auto");
        Files.createDirectories(autoDir);
        Files.writeString(autoDir.resolve("item.txt"), "item content");

        handler = new StaticHandler();
    }

    @Test
    void servesStaticFileDirectly() {
        Route route = new Route("/", Set.of("GET"), root, "index.html", false, null, Map.of());
        Request request = Request.of("GET", "/style.css", "localhost");

        Response resp = handler.handle(request, route);
        assertEquals(200, resp.getStatusCode());
        assertEquals("text/css; charset=utf-8", resp.getHeaders().get("content-type"));
        assertEquals("body { margin: 0; }", new String(resp.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void servesDefaultIndexOnDirectoryWithTrailingSlash() {
        Route route = new Route("/", Set.of("GET"), root, "index.html", false, null, Map.of());
        Request request = Request.of("GET", "/", "localhost");

        Response resp = handler.handle(request, route);
        assertEquals(200, resp.getStatusCode());
        assertEquals("text/html; charset=utf-8", resp.getHeaders().get("content-type"));
        assertEquals("<h1>Home</h1>", new String(resp.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void redirectsDirectoryWithoutTrailingSlash() {
        Route route = new Route("/", Set.of("GET"), root, "index.html", false, null, Map.of());
        Request request = Request.of("GET", "/sub", "localhost");

        Response resp = handler.handle(request, route);
        assertEquals(301, resp.getStatusCode());
        assertEquals("/sub/", resp.getHeaders().get("location"));
    }

    @Test
    void servesAutoindexWhenNoIndexAndAutoindexTrue() {
        Route route = new Route("/auto", Set.of("GET"), root.resolve("auto"), "index.html", true, null, Map.of());
        Request request = Request.of("GET", "/auto/", "localhost");

        Response resp = handler.handle(request, route);
        assertEquals(200, resp.getStatusCode());
        assertEquals("text/html; charset=utf-8", resp.getHeaders().get("content-type"));
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("Index of /auto/"));
        assertTrue(body.contains("item.txt"));
    }

    @Test
    void returns403WhenNoIndexAndAutoindexFalse() {
        Route route = new Route("/auto", Set.of("GET"), root.resolve("auto"), "index.html", false, null, Map.of());
        Request request = Request.of("GET", "/auto/", "localhost");

        Response resp = handler.handle(request, route);
        assertEquals(403, resp.getStatusCode());
    }

    @Test
    void returns404WhenFileNotFound() {
        Route route = new Route("/", Set.of("GET"), root, "index.html", false, null, Map.of());
        Request request = Request.of("GET", "/notfound.html", "localhost");

        Response resp = handler.handle(request, route);
        assertEquals(404, resp.getStatusCode());
    }

    @Test
    void returns403OnTraversalAttempt() {
        Route route = new Route("/", Set.of("GET"), root, "index.html", false, null, Map.of());
        Request request = Request.of("GET", "/../outside", "localhost");

        Response resp = handler.handle(request, route);
        assertEquals(403, resp.getStatusCode());
    }
}
