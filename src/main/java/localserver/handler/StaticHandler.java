package localserver.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Serves static files (and directory listings when autoindex is on).
 * Was GetHandler.
 */
public class StaticHandler implements Handler {

    private static final long MAX_STATIC_FILE_SIZE = 16L * 1024 * 1024; // 16 MB

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    @Override
    public Response handle(Request request, Route route) {
        if (route == null || route.root() == null) {
            return ErrorPages.render(404);
        }

        // Strip the route prefix to get the path relative to the route root
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

        // Resolve the file path safely (no traversal outside root)
        Path target = PathResolver.resolve(route.root(), rel);
        if (target == null) {
            return ErrorPages.render(403);
        }

        if (!Files.exists(target)) {
            return ErrorPages.render(404);
        }

        // ---------- directory ----------
        if (Files.isDirectory(target)) {
            // Redirect so relative links in the listing/index page work
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

            // 1. Try the configured index file
            String indexName = route.index() != null && !route.index().isEmpty()
                    ? route.index()
                    : "index.html";
            Path indexPath = target.resolve(indexName).normalize();
            if (indexPath.startsWith(target)
                    && Files.isRegularFile(indexPath)
                    && Files.isReadable(indexPath)) {
                return serveFile(indexPath);
            }

            // 2. Autoindex
            if (route.autoindex()) {
                String html = Html.directoryListing(reqPath, target);
                Response response = new Response();
                response.setStatus(200);
                response.setBody(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
                return response;
            }

            // 3. No index, autoindex off -> 403
            return ErrorPages.render(403);
        }

        // ---------- regular file ----------
        if (Files.isRegularFile(target)) {
            if (!Files.isReadable(target)) {
                return ErrorPages.render(403);
            }
            return serveFile(target);
        }

        return ErrorPages.render(404);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Response serveFile(Path file) {
        try {
            long size = Files.size(file);
            if (size > MAX_STATIC_FILE_SIZE) {
                System.out.println(size);
                return ErrorPages.render(413);
            }
            byte[] bytes = Files.readAllBytes(file);

            Response response = new Response();
            response.setStatus(200);
            response.setBody(bytes, Mime.forPath(file.getFileName().toString()));
            response.addHeader("Last-Modified",
                    HTTP_DATE.format(Files.getLastModifiedTime(file).toInstant()));
            return response;
        } catch (IOException e) {
            return ErrorPages.render(500);
        }
    }
}