package localserver.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Handles file uploads (multipart and raw body). Was PostHandler.
 */
public class UploadHandler implements Handler {

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
        while (rel.endsWith("/")) {
            rel = rel.substring(0, rel.length() - 1);
        }

        String contentType = contentType(request);
        byte[] body = bodyBytes(request);

        if (request.body() == null || body.length == 0) {
            return ErrorPages.render(400);
        }

        if (contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            return handleMultipart(route, rel, contentType, body);
        }
        return handleRaw(route, rel, reqPath, body);
    }

    // ── multipart: POST /dir  with one or more file parts ────────────────────

    private Response handleMultipart(Route route, String rel, String contentType, byte[] body) {
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            return ErrorPages.render(400);
        }

        Path dir = PathResolver.resolve(route.root(), rel);
        if (dir == null) {
            return ErrorPages.render(403);
        }
        if (!Files.isDirectory(dir)) {
            return ErrorPages.render(404);
        }

        List<String> saved = new ArrayList<>();
        for (MultipartParser.Part part : MultipartParser.parse(body, boundary)) {
            if (part.filename == null || part.filename.isEmpty()) {
                continue; // plain form field, not a file
            }
            String name = sanitizeFilename(part.filename);
            if (name == null) {
                return ErrorPages.render(400);
            }
            if (route.interpreterFor(name) != null) {
                return ErrorPages.render(403);
            }

            String destRel = rel.isEmpty() ? name : rel + "/" + name;
            Path dest = PathResolver.resolve(route.root(), destRel);
            if (dest == null || !dir.equals(dest.getParent())) {
                return ErrorPages.render(403);
            }
            if (Files.isDirectory(dest)) {
                return ErrorPages.render(409);
            }

            try {
                write(dest, part.content);
            } catch (IOException e) {
                return ErrorPages.render(500);
            }
            saved.add(name);
        }

        if (saved.isEmpty()) {
            return ErrorPages.render(400);
        }
        return created(String.join("\n", saved) + "\n", null);
    }

    // ── raw body: POST /dir/name.ext  with the file as the body ──────────────

    private Response handleRaw(Route route, String rel, String reqPath, byte[] body) {
        if (rel.isEmpty()) {
            return ErrorPages.render(400); // no target filename
        }

        String fileName = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
        if (route.interpreterFor(fileName) != null) {
            return ErrorPages.render(403);
        }

        Path dest = PathResolver.resolve(route.root(), rel);
        if (dest == null) {
            return ErrorPages.render(403);
        }
        Path parent = dest.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return ErrorPages.render(404);
        }
        if (Files.isDirectory(dest)) {
            return ErrorPages.render(409);
        }

        try {
            write(dest, body);
        } catch (IOException e) {
            return ErrorPages.render(500);
        }
        return created("Created " + dest.getFileName() + "\n", reqPath);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Write via a hidden temp file in the same directory, then move into place. */
    private static void write(Path dest, byte[] content) throws IOException {
        Path tmp = Files.createTempFile(dest.getParent(), ".upload-", ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Response created(String text, String location) {
        Response r = new Response();
        r.setStatus(201);
        if (location != null) {
            r.addHeader("Location", location);
        }
        r.setBody(text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
        return r;
    }

    /** Keep only the last path segment; reject empty, hidden or malformed names. */
    private static String sanitizeFilename(String filename) {
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || name.startsWith(".") || name.indexOf('\0') >= 0) {
            return null;
        }
        return name;
    }

    private static String extractBoundary(String contentType) {
        int idx = contentType.toLowerCase(Locale.ROOT).indexOf("boundary=");
        if (idx < 0) {
            return null;
        }
        String b = contentType.substring(idx + "boundary=".length());
        int semi = b.indexOf(';');
        if (semi >= 0) {
            b = b.substring(0, semi);
        }
        b = b.trim();
        if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
            b = b.substring(1, b.length() - 1);
        }
        return b.isEmpty() ? null : b;
    }

    // ── adapt these two to your Request class ────────────────────────────────

    private static String contentType(Request request) {
        String ct = request.headers().first("Content-Type");
        return ct == null ? "" : ct;
    }

    private static byte[] bodyBytes(Request request) {
        if (request.body() == null) return new byte[0];
        try (java.io.InputStream in = request.body().openRead()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            return new byte[0];
        }
    }
}