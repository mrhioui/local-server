package localserver.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HtmlTest {

    @Test
    void escapesSpecialHtmlCharacters() {
        assertEquals("&lt;script&gt;alert(&quot;x &amp; y &#39;z&#39;&quot;)&lt;/script&gt;",
                Html.escape("<script>alert(\"x & y 'z'\")</script>"));
        assertEquals("", Html.escape(null));
        assertEquals("normal text", Html.escape("normal text"));
    }

    @Test
    void generatesDefaultErrorPage() {
        String html = Html.errorPage(404, "Not Found");
        assertTrue(html.contains("404 Not Found"));
        assertTrue(html.contains("<title>404 Not Found</title>"));
    }

    @Test
    void generatesDirectoryListing(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("subfolder"));
        Files.writeString(dir.resolve("test file.txt"), "content");
        Files.writeString(dir.resolve(".hidden"), "secret");

        String listing = Html.directoryListing("/files/", dir);
        assertTrue(listing.contains("Index of /files/"));
        assertTrue(listing.contains("<a href=\"../\">../</a>"));
        assertTrue(listing.contains("subfolder/"));
        assertTrue(listing.contains("test%20file.txt"));
        assertFalse(listing.contains(".hidden"), "Hidden dotfiles must not be shown in directory listing");
    }

    @Test
    void rootDirectoryListingHasNoParentLink(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("file.txt"), "content");
        String listing = Html.directoryListing("/", dir);
        assertFalse(listing.contains("<a href=\"../\">../</a>"));
        assertTrue(listing.contains("file.txt"));
    }
}
