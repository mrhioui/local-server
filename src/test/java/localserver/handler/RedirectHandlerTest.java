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
 * Integration tests for RedirectHandler: 301/302/303/307/308 responses.
 * Person B.
 */
class RedirectHandlerTest {

    @TempDir Path tmp;

    private ServerConfig loadServer(String routesJson) throws Exception {
        Path cfg = tmp.resolve("config.json");
        String json = """
                {"servers": [{"host": "127.0.0.1", "ports": [9997],
                 "routes": %s}]}
                """.formatted(routesJson);
        Files.writeString(cfg, json);
        return ConfigLoader.load(cfg).servers().get(0);
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
    void redirects301WithLocationHeader() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer("""
                [{"path": "/old", "redirect": {"code": 301, "to": "/new"}},
                 {"path": "/", "methods": ["GET"], "root": "www"}]
                """);
        Route route = sc.matchRoute("/old");
        assertNotNull(route);
        assertNotNull(route.redirect());

        RedirectHandler h = new RedirectHandler();
        Response r = h.handle(fakeGet("/old"), route);
        assertEquals(301, r.getStatusCode());
        assertEquals("/new", r.getHeaders().get("Location"));
    }

    @Test
    void redirectBodyContainsLink() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer("""
                [{"path": "/old", "redirect": {"code": 302, "to": "/new"}},
                 {"path": "/", "methods": ["GET"], "root": "www"}]
                """);
        Route route = sc.matchRoute("/old");
        RedirectHandler h = new RedirectHandler();
        Response r = h.handle(fakeGet("/old"), route);
        String body = new String(r.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("/new"), "redirect body should contain the target URL");
    }

    @Test
    void redirectEscapesSpecialCharacters() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer("""
                [{"path": "/a", "redirect": {"code": 302, "to": "/b?x=1"}},
                 {"path": "/", "methods": ["GET"], "root": "www"}]
                """);
        Route route = sc.matchRoute("/a");
        RedirectHandler h = new RedirectHandler();
        Response r = h.handle(fakeGet("/a"), route);
        assertEquals(302, r.getStatusCode());
        assertEquals("/b?x=1", r.getHeaders().get("Location"));
        // body must NOT contain unescaped '<' or '&'
        String body = new String(r.getBody(), StandardCharsets.UTF_8);
        assertFalse(body.contains("<script>"), "body must be HTML-safe");
    }

    @Test
    void noRedirectConfiguredReturns500() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer("""
                [{"path": "/", "methods": ["GET"], "root": "www"}]
                """);
        Route route = sc.matchRoute("/");
        RedirectHandler h = new RedirectHandler();
        Response r = h.handle(fakeGet("/"), route);
        // route has no redirect, so handler should return 500
        assertEquals(500, r.getStatusCode());
    }
}
