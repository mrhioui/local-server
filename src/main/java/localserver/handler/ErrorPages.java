package localserver.handler;

import localserver.config.ServerConfig;
import localserver.http.Response;
import localserver.http.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds error responses, using configured custom error pages when present
 * and falling back to a generated page otherwise.
 *
 * STUB (person B).
 */
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import localserver.config.ServerConfig;
import localserver.http.Response;
import localserver.http.Status;

public final class ErrorPages {

    private ErrorPages() {
    }

    /**
     * Build an error response with no server-config context (generic fallback only).
     */
    public static Response render(int statusCode) {
        return render(statusCode, null);
    }

    public static Response render(int statusCode, ServerConfig config) {
        Response response = new Response();
        response.setStatus(statusCode);

        if (config != null) {
            Map<Integer, Path> customPages = config.errorPages();
            Path customPath = customPages != null ? customPages.get(statusCode) : null;
            if (customPath != null && Files.isRegularFile(customPath) && Files.isReadable(customPath)) {
                try {
                    byte[] content = Files.readAllBytes(customPath);
                    response.setBody(content, "text/html; charset=utf-8");
                    return response;
                } catch (IOException ignored) {
                    // Fall back to built-in page
                }
            }
        }

        String reason = Status.reason(statusCode);
        String html = Html.errorPage(statusCode, reason);
        response.setBody(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        return response;
    }
}
