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
        String reason = Status.reasonFor(statusCode);

        // Try custom error page from server config
        if (config != null) {
            Path customPage = config.errorPages().get(statusCode);
            if (customPage != null && Files.isReadable(customPage)) {
                try {
                    byte[] bytes = Files.readAllBytes(customPage);
                    Response r = new Response();
                    r.setStatus(statusCode);
                    r.setBody(bytes, "text/html; charset=utf-8");
                    return r;
                } catch (IOException ignored) {
                    // fall through to generated page
                }
            }
        }

        // Generated fallback
        String html = Html.errorPage(statusCode, reason);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        Response r = new Response();
        r.setStatus(statusCode);
        r.setBody(bytes, "text/html; charset=utf-8");
        return r;
    }
}
