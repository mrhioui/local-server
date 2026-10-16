package localserver.handler;

import localserver.config.ConfigLoader;
import localserver.config.LoadedConfig;
import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Request;
import localserver.http.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for StaticHandler: files, directories, autoindex, index files.
 * Person B.
 */
class StaticHandlerTest {

    @TempDir Path tmp;

    private ServerConfig makeServer(boolean autoindex) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("www"));
        Files.writeString(root.resolve("index.html"), "<html>hello</html>");
        Files.writeString(root.resolve("style.css"), "body{}");
        String json = """
                {"servers": [{"host": "127.0.0.1", "ports": [9998],
                 "routes": [{"path": "/", "root": "www", "autoindex": %s}]}]}
                """.formatted(autoindex);
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, json);
        LoadedConfig loaded = ConfigLoader.load(cfg);
        return loaded.servers().get(0);
    }

    private static Request fakeGet(String path) {
        try {
            var ctor = Request.class.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, String.class,
                    String.class, localserver.http.Headers.class, boolean.class,
                    Request.BodyMode.class, long.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    "GET", path, path, "", "HTTP/1.1", "localhost",
                    new localserver.http.Headers(), true,
                    Request.BodyMode.NONE, 0L, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void servesExistingFile() throws Exception {
        ServerConfig sc = makeServer(false);
        Route route = sc.matchRoute("/index.html");
        StaticHandler h = new StaticHandler();
        Response r = h.handle(fakeGet("/index.html"), route);
        assertEquals(200, r.getStatusCode());
        String body = new String(r.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("hello"));
    }

    @Test
    void setsCorrectMimeType() throws Exception {
        ServerConfig sc = makeServer(false);
        Route route = sc.matchRoute("/style.css");
        StaticHandler h = new StaticHandler();
        Response r = h.handle(fakeGet("/style.css"), route);
        assertEquals(200, r.getStatusCode());
        assertTrue(r.getHeaders().get("Content-Type").contains("text/css"));
    }

    @Test
    void returns404ForMissingFile() throws Exception {
        ServerConfig sc = makeServer(false);
        Route route = sc.matchRoute("/missing.html");
        StaticHandler h = new StaticHandler();
        Response r = h.handle(fakeGet("/missing.html"), route);
        assertEquals(404, r.getStatusCode());
    }

    @Test
    void servesDirectoryIndexFile() throws Exception {
        ServerConfig sc = makeServer(false);
        Route route = sc.matchRoute("/");
        StaticHandler h = new StaticHandler();
        Response r = h.handle(fakeGet("/"), route);
        assertEquals(200, r.getStatusCode());
        assertTrue(new String(r.getBody(), StandardCharsets.UTF_8).contains("hello"));
    }

    @Test
    void autoindexGeneratesDirectoryListing() throws Exception {
        ServerConfig sc = makeServer(true);
        Route route = sc.matchRoute("/");
        StaticHandler h = new StaticHandler();
        Response r = h.handle(fakeGet("/"), route);
        // With autoindex on, even if no index.html, it would list. But index.html exists so it
        // serves it. Let's test autoindex by removing index.html first.
        // Actually our test has index.html so it serves that. Test the listing via a subdir.
        Path subdir = Files.createDirectories(tmp.resolve("www/sub"));
        Files.writeString(subdir.resolve("file.txt"), "content");
        // re-request /sub/ — no index.html there, autoindex=true → listing
        Response r2 = h.handle(fakeGet("/sub/"), route);
        assertEquals(200, r2.getStatusCode());
        String body = new String(r2.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("file.txt"), "listing should contain file.txt");
    }

    @Test
    void returns403WhenNoIndexAndAutoindexOff() throws Exception {
        ServerConfig sc = makeServer(false);
        Route route = sc.matchRoute("/");
        Path subdir = Files.createDirectories(tmp.resolve("www/nolist"));
        StaticHandler h = new StaticHandler();
        Response r = h.handle(fakeGet("/nolist/"), route);
        assertEquals(403, r.getStatusCode());
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        ServerConfig sc = makeServer(false);
        Route route = sc.matchRoute("/");
        StaticHandler h = new StaticHandler();
        Response r = h.handle(fakeGet("/../etc/passwd"), route);
        // Either 403 (traversal blocked) or 404 (normalized away)
        assertTrue(r.getStatusCode() == 403 || r.getStatusCode() == 404,
                "traversal must not return 200, got: " + r.getStatusCode());
    }
}
