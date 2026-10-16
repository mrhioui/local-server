package localserver.handler;

import localserver.config.ConfigLoader;
import localserver.config.LoadedConfig;
import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Request;
import localserver.http.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DeleteHandler: success, missing file, directory, traversal.
 * Person B.
 */
class DeleteHandlerTest {

    @TempDir Path tmp;

    private ServerConfig loadServer() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, """
                {"servers": [{"host": "127.0.0.1", "ports": [9996],
                 "routes": [{"path": "/", "methods": ["GET","DELETE"], "root": "www"}]}]}
                """);
        return ConfigLoader.load(cfg).servers().get(0);
    }

    private static Request fakeDelete(String path) {
        try {
            var ctor = Request.class.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, String.class,
                    String.class, localserver.http.Headers.class, boolean.class,
                    Request.BodyMode.class, long.class, boolean.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    "DELETE", path, path, "", "HTTP/1.1", "localhost",
                    new localserver.http.Headers(), true,
                    Request.BodyMode.NONE, 0L, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deletesExistingFileAndReturns204() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        Files.writeString(www.resolve("target.txt"), "delete me");

        ServerConfig sc = loadServer();
        Route route = sc.matchRoute("/target.txt");
        DeleteHandler h = new DeleteHandler();
        Response r = h.handle(fakeDelete("/target.txt"), route);

        assertEquals(204, r.getStatusCode());
        assertFalse(Files.exists(www.resolve("target.txt")), "file should be deleted");
    }

    @Test
    void returns404ForNonExistentFile() throws Exception {
        Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer();
        Route route = sc.matchRoute("/ghost.txt");
        DeleteHandler h = new DeleteHandler();
        Response r = h.handle(fakeDelete("/ghost.txt"), route);
        assertEquals(404, r.getStatusCode());
    }

    @Test
    void returns403ForDirectory() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        Files.createDirectories(www.resolve("subdir"));

        ServerConfig sc = loadServer();
        Route route = sc.matchRoute("/subdir");
        DeleteHandler h = new DeleteHandler();
        Response r = h.handle(fakeDelete("/subdir"), route);
        assertEquals(403, r.getStatusCode());
    }

    @Test
    void pathTraversalIsRejected() throws Exception {
        Files.createDirectories(tmp.resolve("www"));
        ServerConfig sc = loadServer();
        Route route = sc.matchRoute("/");
        DeleteHandler h = new DeleteHandler();
        Response r = h.handle(fakeDelete("/../outside.txt"), route);
        // 403 (traversal blocked) or 404 (normalized to root)
        assertTrue(r.getStatusCode() == 403 || r.getStatusCode() == 404,
                "traversal must not succeed, got: " + r.getStatusCode());
    }

    @Test
    void deletingFileDoesNotAffectOtherFiles() throws Exception {
        Path www = Files.createDirectories(tmp.resolve("www"));
        Files.writeString(www.resolve("a.txt"), "keep");
        Files.writeString(www.resolve("b.txt"), "delete me");

        ServerConfig sc = loadServer();
        Route route = sc.matchRoute("/b.txt");
        new DeleteHandler().handle(fakeDelete("/b.txt"), route);

        assertTrue(Files.exists(www.resolve("a.txt")), "a.txt must still exist");
        assertFalse(Files.exists(www.resolve("b.txt")), "b.txt must be deleted");
    }
}
