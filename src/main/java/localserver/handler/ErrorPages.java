package localserver.handler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import localserver.config.ServerConfig;
import localserver.http.Response;
import localserver.http.Status;

/**
 * Builds error responses, using configured custom error pages when present
 * and falling back to a generated page otherwise.
 */
public final class ErrorPages {

    private ErrorPages() {
    }

    /**
     * Build an error response with no server-config context (generic fallback only).
     */
    public static Response render(int statusCode) {
        return render(statusCode, null);
    }

    /**
     * Build an error response. If {@code config} contains a custom error page for
     * {@code statusCode}, its HTML file is read and served; otherwise a minimal HTML
     * page is generated inline.
     */
    public static Response render(int statusCode, ServerConfig config) {
        // Try custom error page from server config
        if (config != null) {
            Map<Integer, Path> customPages = config.errorPages();
            Path customPath = customPages != null ? customPages.get(statusCode) : null;

            if (customPath != null
                    && Files.isRegularFile(customPath)
                    && Files.isReadable(customPath)) {
                try {
                    byte[] content = Files.readAllBytes(customPath);
                    Response response = new Response();
                    response.setStatus(statusCode);
                    response.setBody(content, "text/html; charset=utf-8");
                    return response;
                } catch (IOException ignored) {
                    // fall through to generated page
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