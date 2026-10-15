package localserver.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DeleteHandlerTest {

    @TempDir
    Path tempDir;

    private Route createRoute(String path, Path root) {
        return new Route(
                path,
                Set.of("GET", "POST", "DELETE"),
                root,
                null,
                false,
                null,
                Collections.emptyMap()
        );
    }

    @Test
    void deletesExistingFile() throws IOException {
        Route route = createRoute("/files", tempDir);
        Path file = tempDir.resolve("to_delete.txt");
        Files.writeString(file, "goodbye");
        assertTrue(Files.exists(file));

        Request request = Request.of("DELETE", "/files/to_delete.txt", "localhost");
        DeleteHandler handler = new DeleteHandler();
        Response res = handler.handle(request, route);

        assertEquals(204, res.getStatusCode());
        assertFalse(Files.exists(file));
    }

    @Test
    void returns404WhenFileNotFound() {
        Route route = createRoute("/files", tempDir);
        Request request = Request.of("DELETE", "/files/nonexistent.txt", "localhost");

        DeleteHandler handler = new DeleteHandler();
        Response res = handler.handle(request, route);

        assertEquals(404, res.getStatusCode());
    }

    @Test
    void returns403WhenTargetIsDirectory() throws IOException {
        Route route = createRoute("/files", tempDir);
        Path subDir = tempDir.resolve("sub");
        Files.createDirectory(subDir);

        Request request = Request.of("DELETE", "/files/sub", "localhost");
        DeleteHandler handler = new DeleteHandler();
        Response res = handler.handle(request, route);

        assertEquals(403, res.getStatusCode());
        assertTrue(Files.exists(subDir));
    }

    @Test
    void returns403WhenTargetIsRouteRoot() {
        Route route = createRoute("/files", tempDir);
        Request request = Request.of("DELETE", "/files", "localhost");

        DeleteHandler handler = new DeleteHandler();
        Response res = handler.handle(request, route);

        assertEquals(403, res.getStatusCode());
    }

    @Test
    void returns403WhenTargetIsHidden() throws IOException {
        Route route = createRoute("/files", tempDir);
        Path hiddenFile = tempDir.resolve(".secret");
        Files.writeString(hiddenFile, "shh");

        Request request = Request.of("DELETE", "/files/.secret", "localhost");
        DeleteHandler handler = new DeleteHandler();
        Response res = handler.handle(request, route);

        assertEquals(403, res.getStatusCode());
        assertTrue(Files.exists(hiddenFile));
    }

    @Test
    void returns403OnTraversal() {
        Route route = createRoute("/files", tempDir);
        Request request = Request.of("DELETE", "/files/../../etc/passwd", "localhost");

        DeleteHandler handler = new DeleteHandler();
        Response res = handler.handle(request, route);

        assertEquals(403, res.getStatusCode());
    }
}
