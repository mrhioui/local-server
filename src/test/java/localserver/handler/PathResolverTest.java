package localserver.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathResolverTest {

    @TempDir
    Path tmp;

    @Test
    void normalRelativePath() {
        String root = tmp.toString();
        String result = PathResolver.resolve(root, "/index.html");
        assertNotNull(result);
        assertTrue(result.startsWith(root));
        assertTrue(result.endsWith("index.html"));
    }

    @Test
    void nestedPath() {
        String root = tmp.toString();
        String result = PathResolver.resolve(root, "/images/logo.png");
        assertNotNull(result);
        assertTrue(result.contains("images"));
    }

    @Test
    void traversalIsRejected() {
        String root = tmp.toString();
        assertNull(PathResolver.resolve(root, "/../etc/passwd"));
        assertNull(PathResolver.resolve(root, "../../etc/passwd"));
    }

    @Test
    void dotDotInMiddleIsRejected() {
        String root = tmp.toString();
        // /subdir/../../etc/passwd would escape root
        assertNull(PathResolver.resolve(root, "/subdir/../../etc/passwd"));
    }

    @Test
    void dotDotThatStaysInsideRootIsOk() {
        String root = tmp.toString();
        // /a/../b stays inside root
        String result = PathResolver.resolve(root, "/a/../b");
        assertNotNull(result);
        assertTrue(result.startsWith(root));
    }

    @Test
    void nullRootReturnsNull() {
        assertNull(PathResolver.resolve((String) null, "/index.html"));
    }

    @Test
    void nullPathReturnsNull() {
        assertNull(PathResolver.resolve(tmp.toString(), null));
    }

    @Test
    void pathOverloadWorks() {
        String result = PathResolver.resolve(tmp, "/test.html");
        assertNotNull(result);
        assertTrue(result.startsWith(tmp.toAbsolutePath().toString()));
    }

    @Test
    void rootItselfIsAllowed() {
        String result = PathResolver.resolve(tmp.toString(), "/");
        assertNotNull(result);
        assertEquals(tmp.toAbsolutePath().toString(), result);
    }
}

