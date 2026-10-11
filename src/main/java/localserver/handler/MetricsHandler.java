package localserver.handler;

import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Exposes a small metrics/status endpoint (e.g. live session count, uptime).
 *
 * NEW (person B), bonus. STUB.
 */
public class MetricsHandler implements Handler {
    @Override
    public Response handle(Request request, Route route) {
        throw new UnsupportedOperationException("TODO: implement MetricsHandler");
    }
}
