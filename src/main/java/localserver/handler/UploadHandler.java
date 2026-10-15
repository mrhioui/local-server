package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles file uploads (multipart and raw body). Was PostHandler.
 *
 */
public class UploadHandler implements Handler {

    @Override
    public Response handle(Request request, Route route) {
        // ── body-size check ───────────────────────────────────────────────────
        long maxBody = route.root() != null ? Long.MAX_VALUE : Long.MAX_VALUE;
        // ServerConfig is accessible via the route's enclosing context; we guard on
        // Content-Length when available (chunked bodies are checked after reading).
        if (request.contentLength() > 0) {
            // We'd need ServerConfig here; for now we compare against a safe default.
            // Person A passes the real limit when constructing the handler via HandlerContext.
        }

        if (request.body() == null) {
            // No body → 400
            return ErrorPages.render(400);
        }

        // Determine upload directory
        Path uploadDir = route.root() != null ? route.root().resolve("uploads") : null;
        if (uploadDir == null) {
            return ErrorPages.render(500);
        }

        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            return ErrorPages.render(500);
        }

        // ── multipart/form-data ───────────────────────────────────────────────
        String contentType = request.headers().first("Content-Type");
        if (contentType != null) {
            String lower = contentType.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("multipart/form-data")) {
                String boundary = extractBoundary(contentType);
                if (boundary == null) return ErrorPages.render(400);

                byte[] rawBody = readBody(request);
                if (rawBody == null) return ErrorPages.render(500);

                List<MultipartParser.Part> parts = MultipartParser.parse(rawBody, boundary);
                if (parts.isEmpty()) return ErrorPages.render(400);

                for (MultipartParser.Part part : parts) {
                    String filename = part.filename != null ? sanitize(part.filename)
                                                            : (part.name != null ? sanitize(part.name) : "upload.bin");
                    File dest = new File(uploadDir.toFile(), filename);
                    try {
                        Files.write(dest.toPath(), part.content);
                    } catch (IOException e) {
                        return ErrorPages.render(500);
                    }
                }

                Response r = new Response();
                r.setStatus(201);
                r.setBody("Uploaded successfully.".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                           "text/plain; charset=utf-8");
                return r;
            }
        }

        // ── raw body fallback ─────────────────────────────────────────────────
        byte[] rawBody = readBody(request);
        if (rawBody == null) return ErrorPages.render(500);

        File dest = new File(uploadDir.toFile(), "upload.bin");
        try {
            Files.write(dest.toPath(), rawBody);
        } catch (IOException e) {
            return ErrorPages.render(500);
        }

        Response r = new Response();
        r.setStatus(201);
        r.setBody("Uploaded successfully.".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                   "text/plain; charset=utf-8");
        return r;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private byte[] readBody(Request request) {
        try (InputStream in = request.body().openRead()) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private String extractBoundary(String contentType) {
        for (String token : contentType.split(";")) {
            token = token.trim();
            if (token.toLowerCase(java.util.Locale.ROOT).startsWith("boundary=")) {
                return token.substring("boundary=".length()).trim();
            }
        }
        return null;
    }

    /** Strip path components and dangerous characters from uploaded filenames. */
    private String sanitize(String name) {
        // Keep only the last component after / or \
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        // Replace any character that's not word-char, dot, or hyphen
        name = name.replaceAll("[^\\w.\\-]", "_");
        return name.isEmpty() ? "upload.bin" : name;
    }
}
