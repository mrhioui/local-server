package localserver.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.http.Request;
import localserver.http.Response;
import localserver.server.CgiJob;

/**
 * Runs CGI scripts and relays their output.
 */
public class CgiHandler implements Handler {

    public record PreparedCgi(CgiJob job, Response errorResponse) {
        public static PreparedCgi of(CgiJob job) {
            return new PreparedCgi(job, null);
        }
        public static PreparedCgi error(Response errorResponse) {
            return new PreparedCgi(null, errorResponse);
        }
    }

    private final Path tempDir;
    private final long timeoutNanos;
    private final ServerConfig config;

    public CgiHandler() {
        this(Path.of(System.getProperty("java.io.tmpdir")), 5_000_000_000L, null);
    }

    public CgiHandler(Path tempDir, long timeoutNanos) {
        this(tempDir, timeoutNanos, null);
    }

    public CgiHandler(Path tempDir, long timeoutNanos, ServerConfig config) {
        this.tempDir = tempDir;
        this.timeoutNanos = timeoutNanos;
        this.config = config;
    }

    public PreparedCgi prepare(Request request, Route route) {
        if (route == null || route.root() == null) {
            return PreparedCgi.error(ErrorPages.render(404, config));
        }

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

        if (rel.isEmpty() && route.index() != null) {
            rel = route.index();
        }

        if (rel.isEmpty()) {
            return PreparedCgi.error(ErrorPages.render(404, config));
        }

        Path scriptPath = PathResolver.resolve(route.root(), rel);
        if (scriptPath == null || !Files.exists(scriptPath) || Files.isDirectory(scriptPath)) {
            return PreparedCgi.error(ErrorPages.render(404, config));
        }

        String interpreter = route.interpreterFor(scriptPath.getFileName().toString());
        if (interpreter == null && !Files.isExecutable(scriptPath)) {
            return PreparedCgi.error(ErrorPages.render(403, config));
        }

        try {
            CgiJob job = CgiJob.start(request, route, scriptPath, interpreter, tempDir, config, timeoutNanos);
            return PreparedCgi.of(job);
        } catch (IOException e) {
            return PreparedCgi.error(ErrorPages.render(500, config));
        }
    }

    @Override
    public Response handle(Request request, Route route) {
        PreparedCgi prep = prepare(request, route);
        if (prep.errorResponse() != null) {
            return prep.errorResponse();
        }
        CgiJob job = prep.job();
        while (!job.isDone()) {
            if (job.isTimedOut(System.nanoTime())) {
                job.destroy();
                return ErrorPages.render(504, config);
            }
            Thread.onSpinWait();
        }
        return job.finish();
    }
}

