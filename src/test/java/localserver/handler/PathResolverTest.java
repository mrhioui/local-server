package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathResolverTest {

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = tempDir.resolve("root");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("index.html"), "hello");
        Files.writeString(root.resolve("sub/doc.txt"), "sub doc");
    }

    @Test
    void resolvesRootAndDirectFiles() {
        Path resEmpty = PathResolver.resolve(root, "");
        assertNotNull(resEmpty);
        assertEquals(root.toAbsolutePath().normalize(), resEmpty.toAbsolutePath().normalize());

        Path resSlash = PathResolver.resolve(root, "/");
        assertNotNull(resSlash);
        assertEquals(root.toAbsolutePath().normalize(), resSlash.toAbsolutePath().normalize());

        Path resFile = PathResolver.resolve(root, "index.html");
        assertNotNull(resFile);
        assertEquals(root.resolve("index.html").toAbsolutePath().normalize(), resFile.toAbsolutePath().normalize());

        Path resSub = PathResolver.resolve(root, "sub/doc.txt");
        assertNotNull(resSub);
        assertEquals(root.resolve("sub/doc.txt").toAbsolutePath().normalize(), resSub.toAbsolutePath().normalize());
    }

    @Test
    void rejectsPathTraversal() {
        assertNull(PathResolver.resolve(root, "../outside.txt"));
        assertNull(PathResolver.resolve(root, "sub/../../outside.txt"));
        assertNull(PathResolver.resolve(root, "../../etc/passwd"));
    }

    @Test
    void rejectsHiddenFilesAndDots() {
        assertNull(PathResolver.resolve(root, ".hidden"));
        assertNull(PathResolver.resolve(root, "sub/.env"));
        assertNull(PathResolver.resolve(root, ".."));
    }

    @Test
    void rejectsBackslashesAndNulBytes() {
        assertNull(PathResolver.resolve(root, "foo\\bar"));
        assertNull(PathResolver.resolve(root, "foo\0bar"));
    }

    @Test
    void rejectsSymlinkEscapingRoot() throws IOException {
        Path outsideFile = tempDir.resolve("outside.txt");
        Files.writeString(outsideFile, "secret");
        Path symlink = root.resolve("leak.txt");
        try {
            Files.createSymbolicLink(symlink, outsideFile);
            assertNull(PathResolver.resolve(root, "leak.txt"), "Symlink escaping root must be rejected");
        } catch (UnsupportedOperationException | IOException ignored) {
            // Environment might not support symlinks, skip if unsupported
        }
    }
}
