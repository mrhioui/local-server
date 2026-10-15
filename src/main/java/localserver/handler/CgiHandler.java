package localserver.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import localserver.config.Route;
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
        this(Path.of(System.getProperty("java.io.tmpdir")), 5_000_000_000L); // 5 seconds default
    }

    public CgiHandler(Path tempDir, long timeoutNanos) {
        this.tempDir = tempDir;
        this.timeoutNanos = timeoutNanos;
    }

    @Override
    public Response handle(Request request, Route route) {
        if (route == null || route.root() == null) {
            return ErrorPages.render(404);
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
            return ErrorPages.render(404);
        }

        Path scriptPath = PathResolver.resolve(route.root(), rel);
        if (scriptPath == null || !Files.exists(scriptPath) || Files.isDirectory(scriptPath)) {
            return ErrorPages.render(404);
        }

        String interpreter = route.interpreterFor(scriptPath.getFileName().toString());
        if (interpreter == null && !Files.isExecutable(scriptPath)) {
            return ErrorPages.render(403);
        }

        try {
            CgiJob job = CgiJob.start(request, route, scriptPath, interpreter, tempDir, null, timeoutNanos);
            while (!job.isDone()) {
                if (job.isTimedOut(System.nanoTime())) {
                    job.destroy();
                    return ErrorPages.render(504);
                }
                Thread.onSpinWait();
            }
            return job.finish();
        } catch (IOException e) {
            return ErrorPages.render(500);
        }
    }
}
