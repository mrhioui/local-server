package localserver.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Headers;
import localserver.http.Request;
import org.junit.jupiter.api.Test;

class RouterTest {

    private ServerConfig createServerConfig(List<Route> routes) {
        // Sort routes longest first, as ConfigLoader does
        List<Route> sorted = routes.stream()
                .sorted((a, b) -> Integer.compare(b.path().length(), a.path().length()))
                .toList();
        return new ServerConfig(0, "127.0.0.1", List.of(8080), List.of("example.com"), true, 1048576, Map.of(), sorted);
    }

    private Request requestFor(String path) {
        return Request.of("GET", path, "example.com");
    }

    @Test
    void matchesRootRoute() {
        Route rootRoute = new Route("/", Set.of("GET"), Path.of("/var/www"), "index.html", false, null, Map.of());
        Router router = new Router(createServerConfig(List.of(rootRoute)));

        RouteResult resRoot = router.route(requestFor("/"));
        assertTrue(resRoot.isMatched());
        assertEquals("/", resRoot.getRoute().path());
        assertEquals("", resRoot.getRelativePath());

        RouteResult resFile = router.route(requestFor("/index.html"));
        assertTrue(resFile.isMatched());
        assertEquals("index.html", resFile.getRelativePath());

        RouteResult resSub = router.route(requestFor("/sub/page.html"));
        assertTrue(resSub.isMatched());
        assertEquals("sub/page.html", resSub.getRelativePath());
    }

    @Test
    void longestPrefixWins() {
        Route rootRoute = new Route("/", Set.of("GET"), Path.of("/var/www"), "index.html", false, null, Map.of());
        Route staticRoute = new Route("/static", Set.of("GET"), Path.of("/var/www/static"), "index.html", false, null, Map.of());
        Route cssRoute = new Route("/static/css", Set.of("GET"), Path.of("/var/www/static/css"), "index.html", false, null, Map.of());

        Router router = new Router(createServerConfig(List.of(rootRoute, staticRoute, cssRoute)));

        RouteResult res = router.route(requestFor("/static/css/main.css"));
        assertTrue(res.isMatched());
        assertEquals("/static/css", res.getRoute().path());
        assertEquals("main.css", res.getRelativePath());

        RouteResult resStatic = router.route(requestFor("/static/logo.png"));
        assertTrue(resStatic.isMatched());
        assertEquals("/static", resStatic.getRoute().path());
        assertEquals("logo.png", resStatic.getRelativePath());
    }

    @Test
    void wholeSegmentMatching() {
        Route staticRoute = new Route("/static", Set.of("GET"), Path.of("/var/www/static"), "index.html", false, null, Map.of());
        Route rootRoute = new Route("/", Set.of("GET"), Path.of("/var/www"), "index.html", false, null, Map.of());

        Router router = new Router(createServerConfig(List.of(rootRoute, staticRoute)));

        // "/static-other" must NOT match "/static", it falls back to "/"
        RouteResult res = router.route(requestFor("/static-other"));
        assertTrue(res.isMatched());
        assertEquals("/", res.getRoute().path());
        assertEquals("static-other", res.getRelativePath());
    }

    @Test
    void noMatchWhenNoRootRoute() {
        Route apiRoute = new Route("/api", Set.of("GET"), Path.of("/var/www/api"), "index.html", false, null, Map.of());
        Router router = new Router(createServerConfig(List.of(apiRoute)));

        RouteResult res = router.route(requestFor("/other"));
        assertFalse(res.isMatched());
        assertNull(res.getRoute());
        assertEquals("", res.getRelativePath());
    }
}
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
