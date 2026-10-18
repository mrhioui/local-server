package localserver.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import localserver.config.ServerConfig;
import localserver.http.Response;
import localserver.http.Status;

/**
 * Builds error responses, using configured custom error pages when present
 * and falling back to a generated page otherwise.
 */
public final class ErrorPages {

    private static final Map<Path, byte[]> CACHE = new ConcurrentHashMap<>();

    private ErrorPages() {
    }

    /**
     * Preload all custom error pages configured for the server block.
     */
    public static void preload(ServerConfig config) {
        if (config == null || config.errorPages() == null) {
            return;
        }
        for (Path path : config.errorPages().values()) {
            if (path != null && Files.isRegularFile(path) && Files.isReadable(path)) {
                try {
                    CACHE.put(path.toAbsolutePath().normalize(), Files.readAllBytes(path));
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Build an error response with no server-config context (generic fallback only).
     */
    public static Response render(int statusCode) {
        return render(statusCode, null);
    }

    /**
     * Build an error response. If {@code config} contains a custom error page for
     * {@code statusCode}, its HTML file is read from cache and served; otherwise a minimal HTML
     * page is generated inline.
     */
    public static Response render(int statusCode, ServerConfig config) {
        // Try custom error page from server config
        if (config != null && config.errorPages() != null) {
            Path customPath = config.errorPages().get(statusCode);
            if (customPath != null) {
                Path normalized = customPath.toAbsolutePath().normalize();
                byte[] content = CACHE.computeIfAbsent(normalized, p -> {
                    try {
                        return (Files.isRegularFile(p) && Files.isReadable(p)) ? Files.readAllBytes(p) : null;
                    } catch (IOException e) {
                        return null;
                    }
                });
                if (content != null) {
                    Response response = new Response();
                    response.setStatus(statusCode);
                    response.setBody(content, "text/html; charset=utf-8");
                    return response;
                }
            }
        }

        // Generated fallback
        String reason = Status.reason(statusCode);
        String html = Html.errorPage(statusCode, reason);
        Response response = new Response();
        response.setStatus(statusCode);
        response.setBody(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        return response;
    }
}