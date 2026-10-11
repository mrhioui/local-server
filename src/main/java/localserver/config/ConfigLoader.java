package localserver.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns config.json into validated, immutable objects.
 *
 * <p>Failure policy (two levels):
 * <ul>
 *   <li>File-level problems (unreadable, invalid JSON, wrong top-level shape, or no usable
 *       server block at all) throw {@link ConfigException}: the server must not start.</li>
 *   <li>A problem inside one server block, or a conflict between blocks, rejects only that
 *       block (the later one, for conflicts). The reason is reported in
 *       {@link LoadedConfig#problems()} and the remaining blocks keep working.</li>
 * </ul>
 *
 * <p>Relative paths are resolved against the directory that contains the config file, so
 * behaviour does not depend on the working directory the server was started from.
 */
public final class ConfigLoader {

    static final List<String> SUPPORTED_METHODS = List.of("GET", "POST", "DELETE");

    private static final Set<String> SERVER_KEYS = Set.of(
            "host", "ports", "server_names", "default", "client_max_body_size", "error_pages", "routes");
    private static final Set<String> ROUTE_KEYS = Set.of(
            "path", "methods", "root", "index", "autoindex", "redirect", "cgi");
    private static final Set<String> REDIRECT_KEYS = Set.of("code", "to");
    private static final Set<String> TOP_KEYS = Set.of("servers", "settings");
    private static final Set<String> SETTINGS_KEYS = Set.of(
            "idle_timeout_ms", "keep_alive_timeout_ms", "request_timeout_ms", "max_header_bytes", "max_connections");
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    private static final long DEFAULT_MAX_BODY = 1024L * 1024L; // 1 MiB
    private static final Pattern SERVER_NAME = Pattern.compile("[a-z0-9._-]{1,253}");
    private static final Pattern CGI_EXTENSION = Pattern.compile("\\.[a-z0-9]{1,16}");

    private ConfigLoader() { }

    // ---- entry points ------------------------------------------------------------------

    public static LoadedConfig load(Path file) throws ConfigException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("cannot read config file " + file + ": " + e.getMessage());
        }
        Path base = file.toAbsolutePath().getParent();
        return parse(text, base);
    }

    /** Parses and validates a config document; relative paths resolve against baseDir. */
    public static LoadedConfig parse(String text, Path baseDir) throws ConfigException {
        Object doc;
        try {
            doc = Json.parse(text);
        } catch (JsonException e) {
            throw new ConfigException("invalid JSON - " + e.getMessage());
        }
        if (!(doc instanceof Map<?, ?> top)) {
            throw new ConfigException("the top level must be a JSON object");
        }
        for (Object key : top.keySet()) {
            if (!TOP_KEYS.contains(key)) {
                throw new ConfigException("unknown top-level key \"" + key + "\"");
            }
        }
        if (!(top.get("servers") instanceof List<?> blocks) || blocks.isEmpty()) {
            throw new ConfigException("\"servers\" must be a non-empty array");
        }
        Settings settings = parseSettings(top);

        List<String> problems = new ArrayList<>();
        List<ServerConfig> accepted = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            String where = "servers[" + i + "]";
            List<String> local = new ArrayList<>();
            ServerConfig server = buildServer(i, blocks.get(i), where, baseDir, local);
            if (!local.isEmpty()) {
                problems.addAll(local);
                continue;
            }
            String conflict = findConflict(server, accepted);
            if (conflict != null) {
                problems.add(where + ": " + conflict);
                continue;
            }
            accepted.add(server);
        }

        if (accepted.isEmpty()) {
            throw new ConfigException("no usable server block:\n  - " + String.join("\n  - ", problems));
        }
        return new LoadedConfig(accepted, buildListeners(accepted), problems, settings);
    }

    // ---- settings (global, so any problem is file-level) -----------------------------------

    private static Settings parseSettings(Map<?, ?> top) throws ConfigException {
        Settings d = Settings.defaults();
        if (!top.containsKey("settings")) {
            return d;
        }
        List<String> p = new ArrayList<>();
        Map<String, Object> m = asObject(top.get("settings"), "settings", p);
        int idle = d.idleTimeoutMs();
        int keepAlive = d.keepAliveTimeoutMs();
        int request = d.requestTimeoutMs();
        int header = d.maxHeaderBytes();
        int connections = d.maxConnections();
        if (m != null) {
            rejectUnknownKeys(m, SETTINGS_KEYS, "settings", p);
            idle = intSetting(m, "idle_timeout_ms", idle, 50, 3_600_000, p);
            keepAlive = intSetting(m, "keep_alive_timeout_ms", keepAlive, 50, 3_600_000, p);
            request = intSetting(m, "request_timeout_ms", request, 50, 3_600_000, p);
            header = intSetting(m, "max_header_bytes", header, 256, 1_048_576, p);
            connections = intSetting(m, "max_connections", connections, 1, 100_000, p);
        }
        if (!p.isEmpty()) {
            throw new ConfigException("invalid settings:\n  - " + String.join("\n  - ", p));
        }
        return new Settings(idle, keepAlive, request, header, connections);
    }

    private static int intSetting(Map<String, Object> m, String key, int def, int min, int max, List<String> p) {
        if (!m.containsKey(key)) {
            return def;
        }
        String where = "settings." + key;
        Long v = integer(m.get(key), where, p);
        if (v == null) {
            return def;
        }
        if (v < min || v > max) {
            p.add(where + ": must be between " + min + " and " + max);
            return def;
        }
        return v.intValue();
    }

    // ---- server blocks -----------------------------------------------------------------

    private static ServerConfig buildServer(int index, Object node, String where, Path baseDir, List<String> p) {
        Map<String, Object> m = asObject(node, where, p);
        if (m == null) {
            return null;
        }
        rejectUnknownKeys(m, SERVER_KEYS, where, p);

        String host = string(m, "host", "127.0.0.1", where, p).trim();
        if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace)) {
            p.add(where + ".host: must be a non-empty host name or IP address without spaces");
        }
        if (host.equalsIgnoreCase("localhost")) {
            host = "127.0.0.1"; // one canonical spelling, so conflicts are detected
        }

        List<Integer> ports = parsePorts(m, where, p);
        List<String> names = parseServerNames(m, where, p);
        boolean markedDefault = bool(m, "default", false, where, p);

        long maxBody = DEFAULT_MAX_BODY;
        if (m.containsKey("client_max_body_size")) {
            Long v = integer(m.get("client_max_body_size"), where + ".client_max_body_size", p);
            if (v != null) {
                if (v <= 0) {
                    p.add(where + ".client_max_body_size: must be greater than 0 (bytes)");
                } else {
                    maxBody = v;
                }
            }
        }

        Map<Integer, Path> errorPages = parseErrorPages(m, where, baseDir, p);
        List<Route> routes = parseRoutes(m, where, baseDir, p);

        if (!p.isEmpty()) {
            return null;
        }
        return new ServerConfig(index, host, ports, names, markedDefault, maxBody, errorPages, routes);
    }

    private static List<Integer> parsePorts(Map<String, Object> m, String where, List<String> p) {
        if (!(m.get("ports") instanceof List<?> list) || list.isEmpty()) {
            p.add(where + ".ports: required, a non-empty array of port numbers");
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String w = where + ".ports[" + i + "]";
            Long v = integer(list.get(i), w, p);
            if (v == null) {
                continue;
            }
            if (v < 1 || v > 65535) {
                p.add(w + ": port " + v + " is outside 1-65535");
                continue;
            }
            int port = v.intValue();
            if (out.contains(port)) {
                p.add(w + ": port " + port + " is listed twice in this server");
                continue;
            }
            out.add(port);
        }
        return out;
    }

    private static List<String> parseServerNames(Map<String, Object> m, String where, List<String> p) {
        if (!m.containsKey("server_names")) {
            return List.of();
        }
        if (!(m.get("server_names") instanceof List<?> list)) {
            p.add(where + ".server_names: expected an array of strings");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String w = where + ".server_names[" + i + "]";
            if (!(list.get(i) instanceof String raw)) {
                p.add(w + ": expected a string");
                continue;
            }
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (!SERVER_NAME.matcher(name).matches()) {
                p.add(w + ": invalid server name \"" + raw + "\" (letters, digits, '.', '-' and '_' only)");
            } else if (out.contains(name)) {
                p.add(w + ": \"" + name + "\" is listed twice in this server");
            } else {
                out.add(name);
            }
        }
        return out;
    }

    private static Map<Integer, Path> parseErrorPages(Map<String, Object> m, String where, Path baseDir, List<String> p) {
        if (!m.containsKey("error_pages")) {
            return Map.of();
        }
        Map<String, Object> obj = asObject(m.get("error_pages"), where + ".error_pages", p);
        if (obj == null) {
            return Map.of();
        }
        Map<Integer, Path> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            String w = where + ".error_pages[\"" + e.getKey() + "\"]";
            int code;
            try {
                code = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                p.add(w + ": the key must be an HTTP status code");
                continue;
            }
            if (code < 400 || code > 599) {
                p.add(w + ": status must be between 400 and 599");
                continue;
            }
            if (!(e.getValue() instanceof String file) || file.isBlank()) {
                p.add(w + ": expected a file path");
                continue;
            }
            Path path = baseDir.resolve(file.trim()).normalize();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                p.add(w + ": file not found or not readable: " + path);
                continue;
            }
            out.put(code, path);
        }
        return out;
    }

    // ---- routes ------------------------------------------------------------------------

    private static List<Route> parseRoutes(Map<String, Object> m, String where, Path baseDir, List<String> p) {
        if (!(m.get("routes") instanceof List<?> list) || list.isEmpty()) {
            p.add(where + ".routes: required, a non-empty array");
            return List.of();
        }
        List<Route> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String w = where + ".routes[" + i + "]";
            Route route = buildRoute(list.get(i), w, baseDir, p);
            if (route == null) {
                continue;
            }
            if (!seen.add(route.path())) {
                p.add(w + ".path: duplicate route \"" + route.path() + "\"");
                continue;
            }
            out.add(route);
        }
        // Longest path first (stable sort keeps declaration order for ties): first match wins.
        out.sort(Comparator.comparingInt((Route r) -> r.path().length()).reversed());
        return out;
    }

    private static Route buildRoute(Object node, String where, Path baseDir, List<String> p) {
        Map<String, Object> m = asObject(node, where, p);
        if (m == null) {
            return null;
        }
        int before = p.size();
        rejectUnknownKeys(m, ROUTE_KEYS, where, p);

        String path = normalizeRoutePath(m.get("path"), where, p);
        Set<String> methods = parseMethods(m, where, p);
        Route.Redirect redirect = parseRedirect(m, where, path, p);

        Path root = null;
        if (m.containsKey("root")) {
            root = resolveRoot(m.get("root"), where + ".root", baseDir, p);
        } else if (!m.containsKey("redirect")) {
            // Look at what the route declares, not at whether the redirect parsed: an invalid
            // redirect is already reported and must not cause a second, misleading message.
            p.add(where + ".root: required unless the route is a redirect");
        }

        String index = string(m, "index", "index.html", where, p).trim();
        if (index.isEmpty() || index.contains("/") || index.contains("\\") || index.equals("..") || index.indexOf('\0') >= 0) {
            p.add(where + ".index: must be a plain file name");
        }
        boolean autoindex = bool(m, "autoindex", false, where, p);
        Map<String, String> cgi = parseCgi(m, where, p);

        if (p.size() > before || path == null) {
            return null;
        }
        return new Route(path, methods, root, index, autoindex, redirect, cgi);
    }

    private static String normalizeRoutePath(Object raw, String where, List<String> p) {
        String w = where + ".path";
        if (raw == null) {
            p.add(w + ": required");
            return null;
        }
        if (!(raw instanceof String s)) {
            p.add(w + ": expected a string");
            return null;
        }
        String path = s.trim();
        if (!path.startsWith("/")) {
            p.add(w + ": must start with '/'");
            return null;
        }
        if (path.contains("//") || path.contains("?") || path.contains("#")
                || path.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
            p.add(w + ": contains '//', '?', '#', spaces or control characters");
            return null;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                p.add(w + ": '.' and '..' segments are not allowed");
                return null;
            }
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static Set<String> parseMethods(Map<String, Object> m, String where, List<String> p) {
        if (!m.containsKey("methods")) {
            return new LinkedHashSet<>(List.of("GET")); // nothing becomes writable by accident
        }
        if (!(m.get("methods") instanceof List<?> list) || list.isEmpty()) {
            p.add(where + ".methods: expected a non-empty array of method names");
            return new LinkedHashSet<>();
        }
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String w = where + ".methods[" + i + "]";
            if (!(list.get(i) instanceof String raw)) {
                p.add(w + ": expected a string");
                continue;
            }
            String method = raw.trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_METHODS.contains(method)) {
                p.add(w + ": unsupported method \"" + raw + "\" (supported: " + String.join(", ", SUPPORTED_METHODS) + ")");
                continue;
            }
            out.add(method);
        }
        return out;
    }

    private static Route.Redirect parseRedirect(Map<String, Object> m, String where, String routePath, List<String> p) {
        if (!m.containsKey("redirect")) {
            return null;
        }
        String w = where + ".redirect";
        Map<String, Object> obj = asObject(m.get("redirect"), w, p);
        if (obj == null) {
            return null;
        }
        rejectUnknownKeys(obj, REDIRECT_KEYS, w, p);

        Integer code = null;
        if (!obj.containsKey("code")) {
            p.add(w + ".code: required");
        } else {
            Long v = integer(obj.get("code"), w + ".code", p);
            if (v != null) {
                if (REDIRECT_CODES.contains(v.intValue()) && v == v.intValue()) {
                    code = v.intValue();
                } else {
                    p.add(w + ".code: must be one of 301, 302, 303, 307, 308");
                }
            }
        }

        String to = null;
        if (!(obj.get("to") instanceof String s) || s.isBlank()) {
            p.add(w + ".to: required, a non-empty string");
        } else {
            String target = s.trim();
            if (target.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
                // also blocks CR/LF, which would allow HTTP response splitting via Location
                p.add(w + ".to: must not contain spaces or control characters");
            } else if (target.equals(routePath)) {
                p.add(w + ".to: a route cannot redirect to itself");
            } else {
                to = target;
            }
        }
        return code != null && to != null ? new Route.Redirect(code, to) : null;
    }

    private static Path resolveRoot(Object node, String where, Path baseDir, List<String> p) {
        if (!(node instanceof String s) || s.isBlank()) {
            p.add(where + ": expected a directory path");
            return null;
        }
        Path wanted = baseDir.resolve(s.trim()).normalize();
        try {
            Path real = wanted.toRealPath(); // resolves symlinks: later "stays inside root" checks need this
            if (!Files.isDirectory(real)) {
                p.add(where + ": not a directory: " + wanted);
                return null;
            }
            return real;
        } catch (IOException e) {
            p.add(where + ": directory not found: " + wanted);
            return null;
        }
    }

    private static Map<String, String> parseCgi(Map<String, Object> m, String where, List<String> p) {
        if (!m.containsKey("cgi")) {
            return Map.of();
        }
        Map<String, Object> obj = asObject(m.get("cgi"), where + ".cgi", p);
        if (obj == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            String w = where + ".cgi[\"" + e.getKey() + "\"]";
            String ext = e.getKey().trim().toLowerCase(Locale.ROOT);
            if (!CGI_EXTENSION.matcher(ext).matches()) {
                p.add(w + ": the key must be a file extension such as \".py\"");
                continue;
            }
            if (!(e.getValue() instanceof String interpreter)) {
                p.add(w + ": expected the interpreter path as a string (\"\" runs the file directly)");
                continue;
            }
            out.put(ext, interpreter.trim());
        }
        return out;
    }

    // ---- conflicts between blocks ---------------------------------------------------------

    /** Returns why {@code s} cannot coexist with an already accepted block, or null. */
    private static String findConflict(ServerConfig s, List<ServerConfig> accepted) {
        for (ServerConfig a : accepted) {
            for (int port : s.ports()) {
                if (!a.ports().contains(port) || !hostsOverlap(a.host(), s.host())) {
                    continue;
                }
                if (!a.host().equalsIgnoreCase(s.host())) {
                    return "port " + port + " on " + s.host() + " collides with " + a.label() + " on " + a.host()
                            + " (a wildcard address cannot share a port with another address)";
                }
                // Same listener: sharing is virtual hosting, allowed unless the blocks are indistinguishable.
                String where = s.host() + ":" + port;
                for (String name : s.serverNames()) {
                    if (a.serverNames().contains(name)) {
                        return "server name \"" + name + "\" on " + where + " is already used by " + a.label();
                    }
                }
                if (s.serverNames().isEmpty() && a.serverNames().isEmpty()) {
                    return where + " has no server_names and neither does " + a.label()
                            + " (only one unnamed server can share a listener)";
                }
                if (s.markedDefault() && a.markedDefault()) {
                    return where + " already has a default server: " + a.label();
                }
            }
        }
        return null;
    }

    private static boolean hostsOverlap(String a, String b) {
        return a.equalsIgnoreCase(b) || isWildcard(a) || isWildcard(b);
    }

    private static boolean isWildcard(String host) {
        return host.equals("0.0.0.0") || host.equals("::");
    }

    // ---- listeners -----------------------------------------------------------------------

    private record Key(String host, int port) { }

    private static List<ListenerSpec> buildListeners(List<ServerConfig> servers) {
        Map<Key, List<ServerConfig>> byKey = new LinkedHashMap<>();
        for (ServerConfig s : servers) {
            for (int port : s.ports()) {
                byKey.computeIfAbsent(new Key(s.host().toLowerCase(Locale.ROOT), port), k -> new ArrayList<>()).add(s);
            }
        }
        List<ListenerSpec> out = new ArrayList<>();
        for (Map.Entry<Key, List<ServerConfig>> e : byKey.entrySet()) {
            List<ServerConfig> list = e.getValue();
            ServerConfig def = list.stream().filter(ServerConfig::markedDefault).findFirst().orElse(list.get(0));
            out.add(new ListenerSpec(e.getKey().host(), e.getKey().port(), list, def));
        }
        return out;
    }

    // ---- typed access helpers (collect problems instead of throwing) ------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object node, String where, List<String> p) {
        if (node instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        p.add(where + ": expected an object");
        return null;
    }

    private static void rejectUnknownKeys(Map<String, Object> m, Set<String> allowed, String where, List<String> p) {
        for (String key : m.keySet()) {
            if (!allowed.contains(key)) {
                p.add(where + ": unknown key \"" + key + "\"");
            }
        }
    }

    private static String string(Map<String, Object> m, String key, String def, String where, List<String> p) {
        if (!m.containsKey(key)) {
            return def;
        }
        if (m.get(key) instanceof String s) {
            return s;
        }
        p.add(where + "." + key + ": expected a string");
        return def;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def, String where, List<String> p) {
        if (!m.containsKey(key)) {
            return def;
        }
        if (m.get(key) instanceof Boolean b) {
            return b;
        }
        p.add(where + "." + key + ": expected true or false");
        return def;
    }

    private static Long integer(Object node, String where, List<String> p) {
        if (node instanceof Long l) {
            return l;
        }
        p.add(where + ": expected an integer");
        return null;
    }
}
