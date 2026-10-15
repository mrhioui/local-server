package localserver.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Deletes a regular file under the route's root.
 */
public class DeleteHandler implements Handler {

    @Override
    public Response handle(Request request, Route route) {
        if (route == null || route.root() == null) {
            return ErrorPages.render(404);
        }

        String reqPath = request.path();
        String prefix = route.path();
        String rel;
        if (prefix.equals("/")) {
            rel = reqPath.startsWith("/") ? reqPath.substring(1) : reqPath;
        } else if (reqPath.equals(prefix)) {
            rel = "";
        } else if (reqPath.startsWith(prefix + "/")) {
            rel = reqPath.substring(prefix.length() + 1);
        } else {
            rel = reqPath.substring(prefix.length());
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
        }

        if (rel.isEmpty()) {
            return ErrorPages.render(403);
        }

        Path target = PathResolver.resolve(route.root(), rel);
        if (target == null) {
            return ErrorPages.render(403);
        }

        if (!Files.exists(target)) {
            return ErrorPages.render(404);
        }

        if (Files.isDirectory(target) || !Files.isRegularFile(target)) {
            return ErrorPages.render(403);
        }

        try {
            if (Files.isHidden(target) || (target.getFileName() != null && target.getFileName().toString().startsWith("."))) {
                return ErrorPages.render(403);
            }
            Files.delete(target);
            Response response = new Response();
            response.setStatus(204);
            return response;
        } catch (NoSuchFileException e) {
            return ErrorPages.render(404);
        } catch (IOException | SecurityException e) {
            return ErrorPages.render(403);
        }
    }
}
