package localserver.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import localserver.config.Route;
import localserver.config.ServerConfig;
import localserver.handler.ErrorPages;
import localserver.handler.PathResolver;
import localserver.http.BodySink;
import localserver.http.Request;
import localserver.http.Response;

/**
 * Encapsulates the execution and lifecycle of a CGI process.
 * Does not block: stdin/stdout are redirected to files, and completion
 * is polled via isDone() and isTimedOut().
 */
public class CgiJob {
    private static final long MAX_CGI_OUTPUT = 10 * 1024 * 1024; // 10 MB limit

    private final Process process;
    private final Path stdinFile;
    private final Path stdoutFile;
    private final long deadlineNanos;
    private final ServerConfig config;
    private boolean finished;

    public CgiJob(Process process, Path stdinFile, Path stdoutFile, long deadlineNanos, ServerConfig config) {
        this.process = process;
        this.stdinFile = stdinFile;
        this.stdoutFile = stdoutFile;
        this.deadlineNanos = deadlineNanos;
        this.config = config;
    }

    public boolean isDone() {
        return !process.isAlive();
    }

    public boolean isTimedOut(long nowNanos) {
        return nowNanos >= deadlineNanos;
    }

    public Response finish() {
        if (finished) {
            return ErrorPages.render(500, config);
        }
        finished = true;
        try {
            if (!Files.exists(stdoutFile)) {
                return ErrorPages.render(502, config);
            }
            long size = Files.size(stdoutFile);
            if (size > MAX_CGI_OUTPUT) {
                return ErrorPages.render(502, config);
            }
            byte[] output = Files.readAllBytes(stdoutFile);
            return parseOutput(output, config);
        } catch (IOException e) {
            return ErrorPages.render(500, config);
        } finally {
            destroy();
        }
    }

    public void destroy() {
        try {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        try {
            if (stdinFile != null) {
                Files.deleteIfExists(stdinFile);
            }
        } catch (IOException ignored) {
        }
        try {
            if (stdoutFile != null) {
                Files.deleteIfExists(stdoutFile);
            }
        } catch (IOException ignored) {
        }
    }

    public static CgiJob start(Request request, Route route, Path scriptPath, String interpreter,
                               Path tempDir, ServerConfig config, long timeoutNanos) throws IOException {
        List<String> command = new ArrayList<>();
        if (interpreter == null || interpreter.isEmpty()) {
            command.add(scriptPath.toAbsolutePath().toString());
        } else {
            command.add(interpreter);
            command.add(scriptPath.toAbsolutePath().toString());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        if (scriptPath.getParent() != null) {
            pb.directory(scriptPath.getParent().toFile());
        }

        Map<String, String> env = pb.environment();
        env.clear();

        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SERVER_PROTOCOL", "HTTP/1.1");
        env.put("SERVER_SOFTWARE", "LocalServer/1.0");
        env.put("REQUEST_METHOD", request.method());
        env.put("SCRIPT_NAME", request.path());
        env.put("PATH_INFO", "");
        env.put("QUERY_STRING", request.query() != null ? request.query() : "");
        env.put("REMOTE_ADDR", "127.0.0.1");
        env.put("SERVER_NAME", request.host() != null && !request.host().isEmpty() ? request.host() : "localhost");
        env.put("SERVER_PORT", "8080");

        String ct = request.headers().first("content-type");
        if (ct != null) {
            env.put("CONTENT_TYPE", ct);
        }

        BodySink body = request.body();
        if (body != null && body.size() > 0) {
            env.put("CONTENT_LENGTH", String.valueOf(body.size()));
        }

        // Map request headers, stripping PROXY
        for (Map.Entry<String, List<String>> e : request.headers().asMap().entrySet()) {
            String name = e.getKey();
            if (name.equalsIgnoreCase("Proxy")) {
                continue;
            }
            String envName = "HTTP_" + name.toUpperCase(Locale.ROOT).replace('-', '_');
            env.put(envName, String.join(", ", e.getValue()));
        }

        String sysPath = System.getenv("PATH");
        env.put("PATH", sysPath != null ? sysPath : "/usr/bin:/bin");

        // Stdin redirection
        Path stdinTemp = null;
        if (body != null && body.size() > 0) {
            if (body.file() != null) {
                pb.redirectInput(body.file().toFile());
            } else {
                stdinTemp = Files.createTempFile(tempDir, "cgi-in-", ".tmp");
                try (InputStream in = body.openRead(); OutputStream out = Files.newOutputStream(stdinTemp)) {
                    in.transferTo(out);
                }
                pb.redirectInput(stdinTemp.toFile());
            }
        } else {
            File devNull = new File("/dev/null");
            if (devNull.exists()) {
                pb.redirectInput(ProcessBuilder.Redirect.from(devNull));
            }
        }

        // Stdout redirection
        Path stdoutTemp = Files.createTempFile(tempDir, "cgi-out-", ".tmp");
        pb.redirectOutput(stdoutTemp.toFile());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = pb.start();
        return new CgiJob(process, stdinTemp, stdoutTemp, System.nanoTime() + timeoutNanos, config);
    }

    public static Response parseOutput(byte[] output, ServerConfig config) {
        if (output == null || output.length == 0) {
            return ErrorPages.render(502, config);
        }

        int headerEnd = -1;
        int bodyStart = -1;
        for (int i = 0; i < output.length - 1; i++) {
            if (output[i] == '\r' && i + 3 < output.length && output[i + 1] == '\n' && output[i + 2] == '\r' && output[i + 3] == '\n') {
                headerEnd = i;
                bodyStart = i + 4;
                break;
            } else if (output[i] == '\n' && output[i + 1] == '\n') {
                headerEnd = i;
                bodyStart = i + 2;
                break;
            }
        }

        if (headerEnd == -1) {
            return ErrorPages.render(502, config);
        }

        String headerSection = new String(output, 0, headerEnd, StandardCharsets.ISO_8859_1);
        byte[] body = Arrays.copyOfRange(output, bodyStart, output.length);

        Response response = new Response();
        int status = 200;
        String contentType = null;

        String[] lines = headerSection.split("\r?\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();

            if (name.equalsIgnoreCase("Status")) {
                String[] parts = val.split("\\s+", 2);
                try {
                    status = Integer.parseInt(parts[0]);
                } catch (NumberFormatException ignored) {
                }
            } else if (name.equalsIgnoreCase("Set-Cookie")) {
                response.addSetCookie(val);
            } else if (name.equalsIgnoreCase("Content-Type")) {
                contentType = val;
            } else {
                response.addHeader(name, val);
            }
        }

        if (response.getHeaders().containsKey("location") && status == 200) {
            status = 302;
        }

        if (contentType == null) {
            contentType = "text/html; charset=utf-8";
        }

        response.setStatus(status);
        response.setBody(body, contentType);
        return response;
    }
}
