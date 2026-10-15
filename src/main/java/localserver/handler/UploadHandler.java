package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Handles file uploads (multipart and raw body). Was PostHandler.
 *
 * STUB (person B).
 */
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import localserver.config.Route;
import localserver.http.BodySink;
import localserver.http.Request;
import localserver.http.Response;

public class UploadHandler implements Handler {

    @Override
    public Response handle(Request request, Route route) {
        if (route == null || route.root() == null) {
            return ErrorPages.render(404);
        }

        Path destDir = route.root();
        if (!Files.isDirectory(destDir) || !Files.isWritable(destDir)) {
            return ErrorPages.render(403);
        }

        BodySink bodySink = request.body();
        if (bodySink == null || bodySink.size() == 0) {
            return ErrorPages.render(400);
        }

        String contentType = request.headers().first("content-type");
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
            return handleMultipart(request, route, destDir, contentType);
        } else {
            return handleRawUpload(request, route, destDir);
        }
    }

    private Response handleMultipart(Request request, Route route, Path destDir, String contentType) {
        String boundary = extractBoundary(contentType);
        if (boundary == null || boundary.isEmpty()) {
            return ErrorPages.render(400);
        }

        byte[] bodyBytes;
        try (InputStream in = request.body().openRead()) {
            bodyBytes = in.readAllBytes();
        } catch (IOException e) {
            return ErrorPages.render(500);
        }

        List<MultipartParser.Part> parts = MultipartParser.parse(bodyBytes, boundary);
        if (parts.isEmpty()) {
            return ErrorPages.render(400);
        }

        List<Path> createdFiles = new ArrayList<>();
        String firstUploadedUrl = null;

        for (MultipartParser.Part part : parts) {
            if (part.filename == null || part.filename.isBlank()) {
                continue;
            }
            String safeName = sanitizeFilename(part.filename, route);
            if (safeName == null) {
                // Reject invalid or dangerous filenames (such as CGI extensions)
                cleanupFiles(createdFiles);
                return ErrorPages.render(403);
            }

            Path saved = writeAtomic(destDir, safeName, new ByteArrayInputStream(part.content));
            if (saved == null) {
                cleanupFiles(createdFiles);
                return ErrorPages.render(500);
            }
            createdFiles.add(saved);
            if (firstUploadedUrl == null) {
                firstUploadedUrl = buildResourceUrl(route, safeName);
            }
        }

        if (createdFiles.isEmpty()) {
            return ErrorPages.render(400);
        }

        Response response = new Response();
        response.setStatus(201);
        if (firstUploadedUrl != null) {
            response.addHeader("Location", firstUploadedUrl);
        }
        String bodyText = "Created\n";
        response.setBody(bodyText.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
        return response;
    }

    private Response handleRawUpload(Request request, Route route, Path destDir) {
        // Derive filename from path or query or header
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

        String filename = rel;
        if (filename.isEmpty()) {
            filename = "upload_" + System.currentTimeMillis();
        }

        String safeName = sanitizeFilename(filename, route);
        if (safeName == null) {
            return ErrorPages.render(403);
        }

        try (InputStream in = request.body().openRead()) {
            Path saved = writeAtomic(destDir, safeName, in);
            if (saved == null) {
                return ErrorPages.render(500);
            }
            Response response = new Response();
            response.setStatus(201);
            response.addHeader("Location", buildResourceUrl(route, safeName));
            String bodyText = "Created\n";
            response.setBody(bodyText.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
            return response;
        } catch (IOException e) {
            return ErrorPages.render(500);
        }
    }

    public static String sanitizeFilename(String raw, Route route) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.startsWith(".")) {
            return null;
        }
        if (name.length() > 255) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\0') {
                return null;
            }
        }
        // Critical RCE guard: never accept upload of file configured for CGI
        if (route != null && route.interpreterFor(name) != null) {
            return null;
        }
        return name;
    }

    private static Path writeAtomic(Path destDir, String safeName, InputStream in) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile(destDir, "upload-", ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                in.transferTo(out);
            }
            Path targetFile = destDir.resolve(safeName);
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return targetFile;
        } catch (IOException e) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
            return null;
        }
    }

    private static void cleanupFiles(List<Path> files) {
        for (Path p : files) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {}
        }
    }

    private static String extractBoundary(String contentType) {
        for (String param : contentType.split(";")) {
            param = param.trim();
            if (param.toLowerCase().startsWith("boundary=")) {
                String b = param.substring(9).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static String buildResourceUrl(Route route, String fileName) {
        String base = route.path();
        if (base.endsWith("/")) {
            return base + fileName;
        }
        return base + "/" + fileName;
    }
}
