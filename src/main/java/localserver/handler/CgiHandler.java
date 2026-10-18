package localserver.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import localserver.config.Route;
import localserver.http.HttpException;
import localserver.http.Request;
import localserver.http.Response;
import localserver.server.CgiJob;

/**
 * Runs CGI scripts and relays their output.
 */
public class CgiHandler implements Handler {

    private final Path tempDir;
    private final long timeoutNanos;

    public CgiHandler() {
        this(Path.of(System.getProperty("java.io.tmpdir")), 5_000_000_000L);
    }

    public CgiHandler(Path tempDir, long timeoutNanos) {
        this.tempDir = tempDir;
        this.timeoutNanos = timeoutNanos;
    }

    public CgiJob prepare(Request request, Route route) throws HttpException {
        if (route == null || route.root() == null) {
            throw new HttpException(404, "Route root not configured");
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
            throw new HttpException(404, "Empty CGI script path");
        }

        Path scriptPath = PathResolver.resolve(route.root(), rel);
        if (scriptPath == null || !Files.exists(scriptPath) || Files.isDirectory(scriptPath)) {
            throw new HttpException(404, "CGI script not found");
        }

        String interpreter = route.interpreterFor(scriptPath.getFileName().toString());
        if (interpreter == null && !Files.isExecutable(scriptPath)) {
            throw new HttpException(403, "CGI script is not executable");
        }

        try {
            return CgiJob.start(request, route, scriptPath, interpreter, tempDir, timeoutNanos);
        } catch (IOException e) {
            throw new HttpException(500, "Failed to start CGI process: " + e.getMessage());
        }
    }

    @Override
    public Response handle(Request request, Route route) {
        try {
            CgiJob job = prepare(request, route);
            while (!job.isDone()) {
                if (job.isTimedOut(System.nanoTime())) {
                    job.destroy();
                    return ErrorPages.render(504);
                }
                LockSupport.parkNanos(2_000_000L);
            }
            return job.finish();
        } catch (HttpException e) {
            return ErrorPages.render(e.status());
        }
    }
}