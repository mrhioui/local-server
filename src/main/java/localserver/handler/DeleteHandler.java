package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Deletes a resource under the route's root.
 *
 */
public class DeleteHandler implements Handler {

    @Override
    public Response handle(Request request, Route route) {
        // Resolve and validate path (traversal protection)
        String absolutePath = PathResolver.resolve(route.root(), request.path());
        if (absolutePath == null) {
            return ErrorPages.render(403);
        }

        File target = new File(absolutePath);

        if (!target.exists()) {
            return ErrorPages.render(404);
        }

        // Only allow deleting regular files, not directories
        if (!target.isFile()) {
            return ErrorPages.render(403);
        }

        if (!target.canWrite()) {
            return ErrorPages.render(403);
        }

        try {
            Files.delete(target.toPath());
        } catch (IOException e) {
            return ErrorPages.render(500);
        }

        // 204 No Content — body is suppressed automatically by Response.getBytes()
        Response r = new Response();
        r.setStatus(204);
        return r;
    }
}
