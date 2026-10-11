package localserver.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import localserver.http.HeadReader.Result;
import org.junit.jupiter.api.Test;

class HeadReaderTest {

    private static final String HEAD = "GET /index.html HTTP/1.1\r\nHost: example.test\r\nAccept: */*\r\n\r\n";

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void completesAtTheBlankLine() {
        HeadReader r = new HeadReader(1024);
        ByteBuffer in = buf(HEAD);
        assertEquals(Result.COMPLETE, r.feed(in));
        assertEquals(HEAD, new String(r.bytes(), StandardCharsets.ISO_8859_1));
        assertFalse(in.hasRemaining());
    }

    @Test
    void needsMoreUntilTheBlankLineArrives() {
        HeadReader r = new HeadReader(1024);
        assertFalse(r.hasStarted());
        assertEquals(Result.NEED_MORE, r.feed(buf("GET / HTTP/1.1\r\nHost: x\r\n")));
        assertTrue(r.hasStarted());
        assertEquals(Result.COMPLETE, r.feed(buf("\r\n")));
    }

    @Test
    void sameResultNoMatterWhereTheInputIsSplit() {
        for (int split = 0; split <= HEAD.length(); split++) {
            HeadReader r = new HeadReader(1024);
            Result first = r.feed(buf(HEAD.substring(0, split)));
            Result last = split == HEAD.length() ? first : r.feed(buf(HEAD.substring(split)));
            assertEquals(Result.COMPLETE, last, "split at " + split);
            assertEquals(HEAD, new String(r.bytes(), StandardCharsets.ISO_8859_1), "split at " + split);
        }
    }

    @Test
    void worksOneByteAtATime() {
        HeadReader r = new HeadReader(1024);
        Result result = Result.NEED_MORE;
        for (int i = 0; i < HEAD.length(); i++) {
            assertEquals(Result.NEED_MORE, result, "completed too early at byte " + i);
            result = r.feed(buf(HEAD.substring(i, i + 1)));
        }
        assertEquals(Result.COMPLETE, result);
    }

    @Test
    void stopsRightAfterTheHeadAndLeavesPipelinedBytesInTheBuffer() {
        HeadReader r = new HeadReader(1024);
        String second = "GET /second HTTP/1.1\r\nHost: x\r\n\r\n";
        ByteBuffer in = buf(HEAD + second);
        assertEquals(Result.COMPLETE, r.feed(in));
        byte[] rest = new byte[in.remaining()];
        in.get(rest);
        assertArrayEquals(second.getBytes(StandardCharsets.ISO_8859_1), rest);
    }

    @Test
    void bodyBytesAfterTheHeadAreNotConsumed() {
        HeadReader r = new HeadReader(1024);
        ByteBuffer in = buf("POST / HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello");
        assertEquals(Result.COMPLETE, r.feed(in));
        assertEquals(5, in.remaining());
    }

    @Test
    void aBareNewlineIsNotATerminator() {
        HeadReader r = new HeadReader(1024);
        assertEquals(Result.NEED_MORE, r.feed(buf("GET / HTTP/1.1\nHost: x\n\n")));
    }

    @Test
    void aStrayCarriageReturnDoesNotBreakTheMatch() {
        HeadReader r = new HeadReader(1024);
        // "\r\n\r" then an 'X' resets; the real terminator comes at the end.
        assertEquals(Result.NEED_MORE, r.feed(buf("A\r\n\rX\r\n")));
        assertEquals(Result.COMPLETE, r.feed(buf("\r\n")));
        // "\r\r\n\r\n": the doubled CR must not hide the terminator.
        HeadReader r2 = new HeadReader(1024);
        assertEquals(Result.COMPLETE, r2.feed(buf("A\r\r\n\r\n")));
    }

    @Test
    void tooLargeHeadIsReportedAndStaysReported() {
        HeadReader r = new HeadReader(64);
        ByteBuffer in = buf("GET / HTTP/1.1\r\nX: " + "a".repeat(200));
        assertEquals(Result.TOO_LARGE, r.feed(in));
        assertEquals(Result.TOO_LARGE, r.feed(buf("\r\n\r\n")), "sticky until reset");
    }

    @Test
    void aHeadExactlyAtTheLimitIsAccepted() {
        String head = "GET / HTTP/1.1\r\n\r\n";
        HeadReader r = new HeadReader(head.length());
        assertEquals(Result.COMPLETE, r.feed(buf(head)));
        HeadReader tooSmall = new HeadReader(head.length() - 1);
        assertEquals(Result.TOO_LARGE, tooSmall.feed(buf(head)));
    }

    @Test
    void resetPreparesForTheNextRequest() {
        HeadReader r = new HeadReader(1024);
        assertEquals(Result.COMPLETE, r.feed(buf(HEAD)));
        r.reset();
        assertFalse(r.hasStarted());
        assertEquals(0, r.bytes().length);
        assertEquals(Result.COMPLETE, r.feed(buf("GET /b HTTP/1.1\r\n\r\n")));
        assertEquals("GET /b HTTP/1.1\r\n\r\n", new String(r.bytes(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void largeHeadsGrowTheInternalBufferUpToTheLimit() {
        HeadReader r = new HeadReader(16 * 1024);
        String head = "GET / HTTP/1.1\r\nX: " + "a".repeat(10_000) + "\r\n\r\n";
        assertEquals(Result.COMPLETE, r.feed(buf(head)));
        assertEquals(head.length(), r.bytes().length);
    }
}
