package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Serves static files (and directory listings when autoindex is on).
 * Was GetHandler.
 *
 * STUB (person B).
 */
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

public class StaticHandler implements Handler {

    private static final long MAX_STATIC_FILE_SIZE = 16L * 1024 * 1024; // 16 MB

    @Override
    public Response handle(Request request, Route route) {
        if (route == null || route.root() == null) {
            return ErrorPages.render(404);
        }

        String prefix = route.path();
        String reqPath = request.path();
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

        Path target = PathResolver.resolve(route.root(), rel);
        if (target == null) {
            return ErrorPages.render(403);
        }

        if (!Files.exists(target)) {
            return ErrorPages.render(404);
        }

        if (Files.isDirectory(target)) {
            // Check trailing slash: if request path does not end with '/', redirect with 301
            if (!reqPath.endsWith("/")) {
                Response redir = new Response();
                redir.setStatus(301);
                String location = reqPath + "/";
                if (request.query() != null && !request.query().isEmpty()) {
                    location += "?" + request.query();
                }
                redir.addHeader("Location", location);
                return redir;
            }

            // Check default index file
            String indexName = route.index() != null && !route.index().isEmpty() ? route.index() : "index.html";
            Path indexPath = target.resolve(indexName).normalize();
            if (Files.isRegularFile(indexPath) && Files.isReadable(indexPath)) {
                return serveFile(indexPath);
            }

            // No index file: check autoindex
            if (route.autoindex()) {
                String html = Html.directoryListing(reqPath, target);
                Response response = new Response();
                response.setStatus(200);
                response.setBody(html.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/html; charset=utf-8");
                return response;
            }

            return ErrorPages.render(403);
        }

        if (Files.isRegularFile(target)) {
            if (!Files.isReadable(target)) {
                return ErrorPages.render(403);
            }
            return serveFile(target);
        }

        return ErrorPages.render(404);
    }

    private Response serveFile(Path file) {
        try {
            long size = Files.size(file);
            if (size > MAX_STATIC_FILE_SIZE) {
                return ErrorPages.render(413);
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes((int) size);
            }
            String mime = Mime.forPath(file.getFileName().toString());
            Response response = new Response();
            response.setStatus(200);
            response.setBody(bytes, mime);
            return response;
        } catch (IOException e) {
            return ErrorPages.render(500);
        }
    }
}
