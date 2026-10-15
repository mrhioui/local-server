package localserver.routing;

import localserver.config.ConfigLoader;
import localserver.config.LoadedConfig;
import localserver.config.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal Router tests that build a ServerConfig inline (no real sockets).
 *
 * Person B.
 */
class RouterTest {

    /** Build a minimal JSON config, load it, return the first ServerConfig. */
    private static ServerConfig loadServer(Path root, String routesJson) throws Exception {
        String json = "{ \"servers\": [{ "
                + "\"host\": \"127.0.0.1\", \"ports\": [9999], "
                + "\"routes\": " + routesJson
                + " }] }";
        Path cfg = root.resolve("config.json");
        Files.writeString(cfg, json);
        LoadedConfig loaded = ConfigLoader.load(cfg);
        return loaded.servers().get(0);
    }

    @TempDir Path tmp;

    @Test
    void matchesRootRoute() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer(tmp,
                "[{\"path\":\"/\",\"methods\":[\"GET\"],\"root\":\"www\"}]");
        Router router = new Router(sc);

        var req = fakeGet("/index.html");
        RouteResult rr = router.route(req);

        assertTrue(rr.isMatched(), "Should match / route");
        assertEquals(200, rr.getStatusHint());
    }

    @Test
    void returns404WhenNoRouteMatches() throws Exception {
        Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer(tmp,
                "[{\"path\":\"/static\",\"methods\":[\"GET\"],\"root\":\"www\"}]");
        Router router = new Router(sc);

        var req = fakeGet("/other/path");
        RouteResult rr = router.route(req);

        assertFalse(rr.isMatched());
        assertEquals(404, rr.getStatusHint());
    }

    @Test
    void returns405WhenMethodNotAllowed() throws Exception {
        Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer(tmp,
                "[{\"path\":\"/\",\"methods\":[\"GET\"],\"root\":\"www\"}]");
        Router router = new Router(sc);

        var req = fakeRequest("DELETE", "/index.html");
        RouteResult rr = router.route(req);

        assertFalse(rr.isMatched());
        assertEquals(405, rr.getStatusHint());
        assertNotNull(rr.getRoute(), "Route should still be set for 405");
    }

    @Test
    void longestPrefixWins() throws Exception {
        Files.createDirectories(tmp.resolve("www"));
        Files.createDirectories(tmp.resolve("api"));
        ServerConfig sc = loadServer(tmp,
                "[{\"path\":\"/api/v1\",\"methods\":[\"GET\"],\"root\":\"api\"},"
                + "{\"path\":\"/\",\"methods\":[\"GET\"],\"root\":\"www\"}]");
        Router router = new Router(sc);

        RouteResult rr = router.route(fakeGet("/api/v1/users"));
        assertTrue(rr.isMatched());
        assertEquals("/api/v1", rr.getRoute().path());
    }

    @Test
    void relativePathStripsRoutePrefix() throws Exception {
        Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer(tmp,
                "[{\"path\":\"/static\",\"methods\":[\"GET\"],\"root\":\"www\"}]");
        Router router = new Router(sc);

        RouteResult rr = router.route(fakeGet("/static/img/logo.png"));
        assertTrue(rr.isMatched());
        assertEquals("/img/logo.png", rr.getRelativePath());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static localserver.http.Request fakeGet(String path) {
        return fakeRequest("GET", path);
    }

    private static localserver.http.Request fakeRequest(String method, String path) {
        try {
            var ctor = localserver.http.Request.class.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, String.class,
                    String.class, localserver.http.Headers.class, boolean.class,
                    localserver.http.Request.BodyMode.class, long.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    method, path, path, "", "HTTP/1.1", "test.local",
                    new localserver.http.Headers(), false,
                    localserver.http.Request.BodyMode.NONE, 0L, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
