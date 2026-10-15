package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Serves static files (and directory listings when autoindex is on).
 * Was GetHandler.
 *
 */
public class StaticHandler implements Handler {

    @Override
    public Response handle(Request request, Route route) {
        // Resolve the file path safely (no traversal outside root)
        String absolutePath = PathResolver.resolve(route.root(), request.path());
        if (absolutePath == null) {
            return ErrorPages.render(403);
        }

        File target = new File(absolutePath);

        // ---------- directory ----------
        if (target.isDirectory()) {
            // 1. Try the configured index file
            String indexName = route.index() != null ? route.index() : "index.html";
            File indexFile = new File(target, indexName);
            if (indexFile.isFile() && indexFile.canRead()) {
                return serveFile(indexFile);
            }

            // 2. Autoindex
            if (route.autoindex()) {
                String listing = Html.directoryListing(request.path(), target);
                byte[] bytes = listing.getBytes(StandardCharsets.UTF_8);
                Response r = new Response();
                r.setStatus(200);
                r.setBody(bytes, "text/html; charset=utf-8");
                return r;
            }

            // 3. No index, autoindex off → 403
            return ErrorPages.render(403);
        }

        // ---------- regular file ----------
        if (!target.exists()) {
            return ErrorPages.render(404);
        }
        if (!target.canRead()) {
            return ErrorPages.render(403);
        }

        return serveFile(target);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Response serveFile(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            Response r = new Response();
            r.setStatus(200);
            r.setBody(bytes, Mime.forPath(file.getName()));
            r.addHeader("Last-Modified", new java.util.Date(file.lastModified()).toString());
            return r;
        } catch (IOException e) {
            return ErrorPages.render(500);
        }
    }
}
