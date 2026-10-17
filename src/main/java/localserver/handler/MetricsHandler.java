package localserver.handler;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import localserver.config.Route;
import localserver.http.Request;
import localserver.http.Response;
import localserver.server.Server;
import localserver.session.Session;

/**
 * Exposes a metrics / admin dashboard endpoint (uptime, live sessions, memory, connection stats).
 * Bonus challenge.
 */
public class MetricsHandler implements Handler {

    private final Server server;

    public MetricsHandler() {
        this(null);
    }

    public MetricsHandler(Server server) {
        this.server = server;
    }

    @Override
    public Response handle(Request request, Route route) {
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long usedMem = totalMem - freeMem;
        long maxMem = rt.maxMemory();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        int activeSessions = Session.count();

        long accepted = server != null ? server.stats().accepted() : 0;
        long requests = server != null ? server.stats().requests() : 0;
        long timedOut = server != null ? server.stats().timedOut() : 0;
        long refused = server != null ? server.stats().refused() : 0;
        int openConnections = server != null ? server.stats().open() : 0;

        String accept = request != null && request.headers() != null ? request.headers().first("Accept") : "";
        boolean wantsJson = accept != null && accept.contains("application/json");

        Response response = new Response();
        response.setStatus(200);

        if (wantsJson) {
            String json = String.format(
                "{\"uptime_ms\":%d,\"active_sessions\":%d,\"connections\":{\"open\":%d,\"accepted\":%d,\"requests\":%d,\"timed_out\":%d,\"refused\":%d},\"memory\":{\"used_bytes\":%d,\"total_bytes\":%d,\"max_bytes\":%d}}",
                uptimeMs, activeSessions, openConnections, accepted, requests, timedOut, refused, usedMem, totalMem, maxMem
            );
            response.setBody(json.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
        } else {
            String html = String.format("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>LocalServer Metrics & Admin Dashboard</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 40px; background: #f7fafc; color: #2d3748; }
                    .card { background: white; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.05); padding: 24px; max-width: 600px; margin: 0 auto; }
                    h1 { margin-top: 0; color: #1a202c; font-size: 1.5rem; }
                    table { width: 100%%; border-collapse: collapse; margin-top: 16px; }
                    th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid #edf2f7; }
                    th { color: #718096; font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.05em; }
                    .badge { background: #c6f6d5; color: #22543d; padding: 4px 8px; border-radius: 4px; font-weight: bold; font-size: 0.85rem; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>LocalServer Metrics Dashboard <span class="badge">ONLINE</span></h1>
                    <table>
                      <tr><th>Metric</th><th>Value</th></tr>
                      <tr><td>Uptime</td><td>%d seconds</td></tr>
                      <tr><td>Single-thread Event Loop</td><td>Active (1 worker thread)</td></tr>
                      <tr><td>Current Open Connections</td><td>%d</td></tr>
                      <tr><td>Total Accepted Connections</td><td>%d</td></tr>
                      <tr><td>Total Requests Served</td><td>%d</td></tr>
                      <tr><td>Timed Out Requests</td><td>%d</td></tr>
                      <tr><td>Refused Connections</td><td>%d</td></tr>
                      <tr><td>Active Sessions (In-Memory)</td><td>%d</td></tr>
                      <tr><td>Memory Used</td><td>%.2f MB / %.2f MB</td></tr>
                    </table>
                  </div>
                </body>
                </html>
                """,
                uptimeMs / 1000, openConnections, accepted, requests, timedOut, refused, activeSessions,
                usedMem / (1024.0 * 1024.0), totalMem / (1024.0 * 1024.0)
            );
            response.setBody(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        }
        return response;
    }
}
