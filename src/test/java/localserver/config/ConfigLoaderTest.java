package localserver.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path base;

    @BeforeEach
    void layout() throws IOException {
        Files.createDirectories(base.resolve("www/a"));
        Files.createDirectories(base.resolve("www/b"));
        Files.createDirectories(base.resolve("www/uploads"));
        Files.writeString(base.resolve("404.html"), "not found");
    }

    private LoadedConfig parse(String json) throws ConfigException {
        return ConfigLoader.parse(json, base);
    }

    private static boolean anyProblemContains(LoadedConfig c, String text) {
        return c.problems().stream().anyMatch(p -> p.contains(text));
    }

    // ---- defaults and happy path ---------------------------------------------------------

    @Test
    void minimalServerGetsSafeDefaults() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"ports": [8080], "routes": [{"path": "/", "root": "www/a"}]}]}
                """);
        assertTrue(c.problems().isEmpty());
        ServerConfig s = c.servers().get(0);
        assertEquals("127.0.0.1", s.host());
        assertEquals(List.of(8080), s.ports());
        assertEquals(1024L * 1024L, s.maxBodySize());
        assertFalse(s.markedDefault());
        Route r = s.routes().get(0);
        assertEquals(List.of("GET"), List.copyOf(r.methods()), "nothing is writable unless asked");
        assertEquals("index.html", r.index());
        assertFalse(r.autoindex());
        assertNull(r.redirect());
        assertEquals(base.resolve("www/a").toRealPath(), r.root());
    }

    @Test
    void localhostIsNormalizedToLoopbackAddress() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"host": "LocalHost", "ports": [8080], "routes": [{"path": "/", "root": "www/a"}]}]}
                """);
        assertEquals("127.0.0.1", c.servers().get(0).host());
    }

    // ---- file level failures -------------------------------------------------------------

    @Test
    void invalidJsonRefusesToStart() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("{\"servers\": ["));
        assertTrue(e.getMessage().startsWith("invalid JSON"), e.getMessage());
    }

    @Test
    void wrongTopLevelShapeRefusesToStart() {
        assertThrows(ConfigException.class, () -> parse("[]"));
        assertThrows(ConfigException.class, () -> parse("{}"));
        assertThrows(ConfigException.class, () -> parse("{\"servers\": []}"));
        assertThrows(ConfigException.class, () -> parse("{\"servers\": {}}"));
        ConfigException e = assertThrows(ConfigException.class, () -> parse("{\"servers\": [], \"serverz\": 1}"));
        assertTrue(e.getMessage().contains("serverz"));
    }

    @Test
    void whenEveryBlockIsInvalidItRefusesToStartAndExplainsEach() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("""
                {"servers": [
                  {"ports": [0], "routes": [{"path": "/", "root": "www/a"}]},
                  {"routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """));
        assertTrue(e.getMessage().contains("servers[0].ports[0]"), e.getMessage());
        assertTrue(e.getMessage().contains("servers[1].ports"), e.getMessage());
    }

    @Test
    void loadResolvesRelativePathsAgainstTheConfigFileNotTheWorkingDirectory() throws Exception {
        Path file = base.resolve("my-config.json");
        Files.writeString(file, """
                {"servers": [{"ports": [8080], "routes": [{"path": "/", "root": "www/a"}]}]}
                """);
        LoadedConfig c = ConfigLoader.load(file);
        assertEquals(base.resolve("www/a").toRealPath(), c.servers().get(0).routes().get(0).root());
    }

    @Test
    void loadReportsAMissingFile() {
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(base.resolve("nope.json")));
        assertTrue(e.getMessage().contains("cannot read config file"));
    }

    // ---- one bad block must not take the others down ------------------------------------------

    @Test
    void anInvalidBlockIsSkippedAndTheOthersKeepWorking() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8080], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8081], "typo_key": 1, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8082], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(2, c.servers().size());
        assertEquals(List.of(0, 2), c.servers().stream().map(ServerConfig::index).toList());
        assertEquals(1, c.problems().size());
        assertTrue(c.problems().get(0).contains("servers[1]: unknown key \"typo_key\""), c.problems().get(0));
    }

    @Test
    void theSamePortTwiceInsideOneServerIsAnError() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8080, 8080], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8081], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "port 8080 is listed twice"), c.problems().toString());
    }

    // ---- sharing ports: virtual hosts versus conflicts ---------------------------------------------

    @Test
    void differentServerNamesMaySharePortAndFormOneListener() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8080], "server_names": ["a.test"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8080], "server_names": ["b.test"], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertTrue(c.problems().isEmpty());
        assertEquals(1, c.listeners().size());
        assertEquals(2, c.listeners().get(0).servers().size());
    }

    @Test
    void sameHostPortAndServerNameIsRejectedNamingBothBlocks() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8080], "server_names": ["a.test"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8080], "server_names": ["A.test"], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(1, c.servers().size(), "the later block is the one rejected");
        assertTrue(c.problems().get(0).contains("servers[1]"), c.problems().get(0));
        assertTrue(c.problems().get(0).contains("servers[0]"), c.problems().get(0));
        assertTrue(c.problems().get(0).contains("a.test"), c.problems().get(0));
    }

    @Test
    void twoUnnamedServersOnOneListenerAreIndistinguishable() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8080], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8080], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "only one unnamed server"), c.problems().toString());
    }

    @Test
    void twoDefaultsOnOneListenerAreRejected() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8080], "server_names": ["a.test"], "default": true, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8080], "server_names": ["b.test"], "default": true, "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "already has a default server"), c.problems().toString());
    }

    @Test
    void aWildcardAddressCannotSharePortWithASpecificAddress() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"host": "0.0.0.0", "ports": [8080], "routes": [{"path": "/", "root": "www/a"}]},
                  {"host": "127.0.0.1", "ports": [8080], "server_names": ["b.test"], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "wildcard"), c.problems().toString());
    }

    @Test
    void samePortOnDifferentSpecificAddressesIsFine() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"host": "127.0.0.1", "ports": [8080], "routes": [{"path": "/", "root": "www/a"}]},
                  {"host": "127.0.0.2", "ports": [8080], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertTrue(c.problems().isEmpty());
        assertEquals(2, c.listeners().size());
    }

    @Test
    void localhostAndLoopbackAddressCountAsTheSameListener() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"host": "localhost", "ports": [8080], "routes": [{"path": "/", "root": "www/a"}]},
                  {"host": "127.0.0.1", "ports": [8080], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "only one unnamed server"), c.problems().toString());
    }

    // ---- default server and virtual host selection ------------------------------------------------

    @Test
    void firstDeclaredServerIsTheDefaultUnlessOneIsMarked() throws Exception {
        LoadedConfig plain = parse("""
                {"servers": [
                  {"ports": [8080], "server_names": ["a.test"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8080], "server_names": ["b.test"], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(0, plain.listeners().get(0).defaultServer().index());

        LoadedConfig marked = parse("""
                {"servers": [
                  {"ports": [8080], "server_names": ["a.test"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8080], "server_names": ["b.test"], "default": true, "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        assertEquals(1, marked.listeners().get(0).defaultServer().index());
    }

    @Test
    void hostHeaderSelectsServerAndUnknownOrMissingFallsBackToDefault() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8080], "server_names": ["a.test"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8080], "server_names": ["b.test", "www.b.test"], "routes": [{"path": "/", "root": "www/b"}]}
                ]}
                """);
        ListenerSpec l = c.listeners().get(0);
        assertEquals(0, l.select("a.test").index());
        assertEquals(0, l.select("A.TEST:8080").index(), "case and port are ignored");
        assertEquals(1, l.select("b.test").index());
        assertEquals(1, l.select("www.b.test:8080").index());
        assertSame(l.defaultServer(), l.select("unknown.test"));
        assertSame(l.defaultServer(), l.select(null));
        assertSame(l.defaultServer(), l.select(""));
    }

    @Test
    void normalizeHostHandlesPortsCaseAndIpv6() {
        assertEquals("example.com", ListenerSpec.normalizeHost("Example.COM:8080"));
        assertEquals("example.com", ListenerSpec.normalizeHost("  example.com  "));
        assertEquals("[::1]", ListenerSpec.normalizeHost("[::1]:8080"));
        assertEquals("[::1]", ListenerSpec.normalizeHost("[::1]"));
        assertEquals("", ListenerSpec.normalizeHost(null));
    }

    // ---- routes --------------------------------------------------------------------------------

    @Test
    void routesAreSortedLongestFirstAndMatchWholeSegments() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"ports": [8080], "routes": [
                  {"path": "/",          "root": "www/a"},
                  {"path": "/img",       "root": "www/b"},
                  {"path": "/img/icons", "root": "www/uploads"}
                ]}]}
                """);
        ServerConfig s = c.servers().get(0);
        assertEquals(List.of("/img/icons", "/img", "/"), s.routes().stream().map(Route::path).toList());
        assertEquals("/img/icons", s.matchRoute("/img/icons/x.png").path());
        assertEquals("/img", s.matchRoute("/img/a.png").path());
        assertEquals("/img", s.matchRoute("/img").path());
        assertEquals("/", s.matchRoute("/imgfoo/a.png").path(), "/imgfoo must not match the /img route");
        assertEquals("/", s.matchRoute("/anything").path());
    }

    @Test
    void withoutARootRouteUnmatchedPathsGiveNull() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"ports": [8080], "routes": [{"path": "/only", "root": "www/a"}]}]}
                """);
        assertNull(c.servers().get(0).matchRoute("/other"));
        assertNotNull(c.servers().get(0).matchRoute("/only/x"));
    }

    @Test
    void routePathsAreNormalizedAndValidated() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/upload/", "root": "www/a"}]},
                  {"ports": [8082], "routes": [{"path": "/a//b", "root": "www/a"}]},
                  {"ports": [8083], "routes": [{"path": "/a/../b", "root": "www/a"}]},
                  {"ports": [8084], "routes": [{"path": "nope", "root": "www/a"}]},
                  {"ports": [8085], "routes": [{"path": "/a?x=1", "root": "www/a"}]}
                ]}
                """);
        assertEquals("/upload", c.servers().get(0).routes().get(0).path());
        assertEquals(1, c.servers().size());
        assertEquals(4, c.problems().size(), c.problems().toString());
    }

    @Test
    void duplicateRoutePathsAreRejected() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/x", "root": "www/a"}, {"path": "/x/", "root": "www/b"}]},
                  {"ports": [8082], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "duplicate route \"/x\""), c.problems().toString());
    }

    // ---- methods -------------------------------------------------------------------------------

    @Test
    void methodsAreNormalizedKeepOrderAndPowerTheAllowHeader() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"ports": [8080], "routes": [
                  {"path": "/", "root": "www/a", "methods": ["post", "GET", "Delete", "get"]}
                ]}]}
                """);
        Route r = c.servers().get(0).routes().get(0);
        assertEquals("POST, GET, DELETE", r.allowHeader());
        assertTrue(r.allows("DELETE"));
        assertFalse(r.allows("PUT"));
    }

    @Test
    void unsupportedOrMalformedMethodsAreRejected() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/", "root": "www/a", "methods": ["PUT"]}]},
                  {"ports": [8082], "routes": [{"path": "/", "root": "www/a", "methods": []}]},
                  {"ports": [8083], "routes": [{"path": "/", "root": "www/a", "methods": "GET"}]},
                  {"ports": [8084], "routes": [{"path": "/", "root": "www/a", "methods": ["GET"]}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertEquals(3, c.problems().size(), c.problems().toString());
        assertTrue(anyProblemContains(c, "unsupported method \"PUT\""));
    }

    // ---- redirects -----------------------------------------------------------------------------

    @Test
    void redirectRoutesNeedNoRoot() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"ports": [8080], "routes": [
                  {"path": "/", "root": "www/a"},
                  {"path": "/old", "redirect": {"code": 301, "to": "/new"}}
                ]}]}
                """);
        Route old = c.servers().get(0).matchRoute("/old");
        assertEquals(301, old.redirect().code());
        assertEquals("/new", old.redirect().to());
        assertNull(old.root());
    }

    @Test
    void invalidRedirectsAreRejected() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/o", "redirect": {"code": 200, "to": "/x"}}]},
                  {"ports": [8082], "routes": [{"path": "/o", "redirect": {"code": 301}}]},
                  {"ports": [8083], "routes": [{"path": "/o", "redirect": {"to": "/x"}}]},
                  {"ports": [8084], "routes": [{"path": "/o", "redirect": {"code": 301, "to": "/o"}}]},
                  {"ports": [8085], "routes": [{"path": "/o", "redirect": {"code": 301, "to": "/x\\r\\nSet-Cookie: a=b"}}]},
                  {"ports": [8086], "routes": [{"path": "/o", "redirect": {"code": 301, "to": "/x", "extra": 1}}]},
                  {"ports": [8087], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertEquals(6, c.problems().size(), c.problems().toString());
        assertTrue(anyProblemContains(c, "must be one of 301, 302, 303, 307, 308"));
        assertTrue(anyProblemContains(c, "cannot redirect to itself"));
        assertTrue(anyProblemContains(c, "control characters"));
    }

    // ---- files and directories on disk -----------------------------------------------------------

    @Test
    void missingRootDirectoryIsReported() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/", "root": "www/missing"}]},
                  {"ports": [8082], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "directory not found"), c.problems().toString());
    }

    @Test
    void rootThatIsAFileIsReported() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/", "root": "404.html"}]},
                  {"ports": [8082], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertTrue(anyProblemContains(c, "not a directory"), c.problems().toString());
    }

    @Test
    void errorPagesMustExistAndHaveValidStatusKeys() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "error_pages": {"404": "404.html"}, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8082], "error_pages": {"404": "gone.html"}, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8083], "error_pages": {"200": "404.html"}, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8084], "error_pages": {"abc": "404.html"}, "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertEquals(base.resolve("404.html"), c.servers().get(0).errorPages().get(404));
        assertEquals(3, c.problems().size(), c.problems().toString());
    }

    // ---- scalar settings ------------------------------------------------------------------------

    @Test
    void bodyLimitMustBeAPositiveInteger() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "client_max_body_size": 2048, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8082], "client_max_body_size": 0, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8083], "client_max_body_size": -5, "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8084], "client_max_body_size": "1M", "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8085], "client_max_body_size": 1.5, "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertEquals(2048L, c.servers().get(0).maxBodySize());
        assertEquals(4, c.problems().size(), c.problems().toString());
    }

    @Test
    void portsMustBeIntegersInRange() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [0], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [65536], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": ["80"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [], "routes": [{"path": "/", "root": "www/a"}]},
                  {"routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertEquals(5, c.problems().size(), c.problems().toString());
    }

    @Test
    void serverNamesAreLowercasedAndValidated() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "server_names": ["Site-A.Test"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8082], "server_names": ["bad name"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8083], "server_names": ["host:80"], "routes": [{"path": "/", "root": "www/a"}]},
                  {"ports": [8084], "server_names": ["x.test", "X.test"], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(List.of("site-a.test"), c.servers().get(0).serverNames());
        assertEquals(1, c.servers().size());
        assertEquals(3, c.problems().size(), c.problems().toString());
    }

    // ---- CGI and index ----------------------------------------------------------------------------

    @Test
    void cgiExtensionsAreNormalizedAndInterpretersLookedUpByExtension() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"ports": [8080], "routes": [
                  {"path": "/cgi", "root": "www/a", "cgi": {".PY": "/usr/bin/python3", ".bin": ""}}
                ]}]}
                """);
        Route r = c.servers().get(0).routes().get(0);
        assertEquals("/usr/bin/python3", r.interpreterFor("Script.py"));
        assertEquals("", r.interpreterFor("tool.bin"), "empty interpreter means run the file directly");
        assertNull(r.interpreterFor("page.html"));
        assertNull(r.interpreterFor("noextension"));
    }

    @Test
    void badCgiEntriesAreRejected() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/c", "root": "www/a", "cgi": {"py": "/usr/bin/python3"}}]},
                  {"ports": [8082], "routes": [{"path": "/c", "root": "www/a", "cgi": {".py": 3}}]},
                  {"ports": [8083], "routes": [{"path": "/c", "root": "www/a", "cgi": []}]},
                  {"ports": [8084], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertEquals(3, c.problems().size(), c.problems().toString());
    }

    @Test
    void indexMustBeAPlainFileName() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  {"ports": [8081], "routes": [{"path": "/", "root": "www/a", "index": "home.html"}]},
                  {"ports": [8082], "routes": [{"path": "/", "root": "www/a", "index": "../secret"}]},
                  {"ports": [8083], "routes": [{"path": "/", "root": "www/a", "index": ""}]}
                ]}
                """);
        assertEquals("home.html", c.servers().get(0).routes().get(0).index());
        assertEquals(2, c.problems().size(), c.problems().toString());
    }

    // ---- structure errors report where they are ----------------------------------------------------

    @Test
    void wrongTypesProduceMessagesWithAPathToTheField() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [
                  "not an object",
                  {"ports": [8081], "default": "yes", "routes": [{"path": "/", "root": "www/a", "autoindex": 1}]},
                  {"ports": [8082], "routes": [{"path": "/", "root": "www/a"}]}
                ]}
                """);
        assertEquals(1, c.servers().size());
        assertTrue(anyProblemContains(c, "servers[0]: expected an object"), c.problems().toString());
        assertTrue(anyProblemContains(c, "servers[1].default: expected true or false"), c.problems().toString());
        assertTrue(anyProblemContains(c, "servers[1].routes[0].autoindex: expected true or false"), c.problems().toString());
    }

    // ---- the sample config shipped with the project --------------------------------------------------

    @Test
    void theShippedSampleConfigIsFullyValid() throws Exception {
        Path sample = Path.of("config.json");
        if (!Files.exists(sample)) {
            return; // tests run from another directory; nothing to check
        }
        LoadedConfig c = ConfigLoader.load(sample);
        assertTrue(c.problems().isEmpty(), c.problems().toString());
        assertEquals(3, c.servers().size());
    }

    // ---- settings ---------------------------------------------------------------------------------

    @Test
    void settingsDefaultWhenAbsent() throws Exception {
        LoadedConfig c = parse("""
                {"servers": [{"ports": [8080], "routes": [{"path": "/", "root": "www/a"}]}]}
                """);
        assertEquals(Settings.defaults(), c.settings());
    }

    @Test
    void settingsCanBeOverriddenIndividually() throws Exception {
        LoadedConfig c = parse("""
                {"settings": {"idle_timeout_ms": 250, "max_connections": 8},
                 "servers": [{"ports": [8080], "routes": [{"path": "/", "root": "www/a"}]}]}
                """);
        assertEquals(250, c.settings().idleTimeoutMs());
        assertEquals(8, c.settings().maxConnections());
        assertEquals(Settings.defaults().keepAliveTimeoutMs(), c.settings().keepAliveTimeoutMs());
    }

    @Test
    void invalidSettingsRefuseToStart() {
        String routes = "\"servers\": [{\"ports\": [8080], \"routes\": [{\"path\": \"/\", \"root\": \"www/a\"}]}]";
        for (String bad : List.of(
                "{\"idle_timeout_ms\": 0}",            // below the minimum
                "{\"max_connections\": 0}",
                "{\"max_header_bytes\": 10}",
                "{\"request_timeout_ms\": \"fast\"}",   // wrong type
                "{\"idle_timeout_ms\": 1.5}",
                "{\"typo_ms\": 5}",                     // unknown key
                "[]")) {                                // not an object
            ConfigException e = assertThrows(ConfigException.class,
                    () -> parse("{\"settings\": " + bad + ", " + routes + "}"), bad);
            assertTrue(e.getMessage().startsWith("invalid settings"), e.getMessage());
        }
    }
}
