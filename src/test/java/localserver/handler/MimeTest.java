package localserver.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MimeTest {

    @ParameterizedTest
    @CsvSource({
        "index.html, text/html; charset=utf-8",
        "about.htm, text/html; charset=utf-8",
        "style.css, text/css; charset=utf-8",
        "app.js, text/javascript; charset=utf-8",
        "module.mjs, text/javascript; charset=utf-8",
        "data.json, application/json; charset=utf-8",
        "doc.txt, text/plain; charset=utf-8",
        "feed.xml, application/xml; charset=utf-8",
        "table.csv, text/csv; charset=utf-8",
        "image.png, image/png",
        "photo.jpg, image/jpeg",
        "photo.jpeg, image/jpeg",
        "anim.gif, image/gif",
        "vector.svg, image/svg+xml",
        "favicon.ico, image/x-icon",
        "graphic.webp, image/webp",
        "modern.avif, image/avif",
        "font.woff, font/woff",
        "font.woff2, font/woff2",
        "font.ttf, font/ttf",
        "font.otf, font/otf",
        "book.pdf, application/pdf",
        "prog.wasm, application/wasm",
        "archive.zip, application/zip",
        "video.mp4, video/mp4",
        "video.webm, video/webm",
        "audio.mp3, audio/mpeg"
    })
    void mapsStandardExtensionsCorrectly(String filename, String expectedMime) {
        assertEquals(expectedMime, Mime.forPath(filename));
    }

    @Test
    void caseInsensitiveLookup() {
        assertEquals("text/html; charset=utf-8", Mime.forPath("PAGE.HTML"));
        assertEquals("image/png", Mime.forPath("PHOTO.PNG"));
        assertEquals("text/css; charset=utf-8", Mime.forPath("styles.CsS"));
    }

    @Test
    void handlesPathsWithDirectories() {
        assertEquals("text/css; charset=utf-8", Mime.forPath("/var/www/static/css/style.css"));
        assertEquals("image/jpeg", Mime.forPath("relative/path/to/image.jpg"));
    }

    @Test
    void defaultsUnknownOrMissingExtensions() {
        assertEquals("application/octet-stream", Mime.forPath(null));
        assertEquals("application/octet-stream", Mime.forPath("no_extension"));
        assertEquals("application/octet-stream", Mime.forPath("trailing_dot."));
        assertEquals("application/octet-stream", Mime.forPath("file.unknownext123"));
    }
}
