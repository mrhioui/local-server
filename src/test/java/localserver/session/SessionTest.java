package localserver.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class SessionTest {

    @Test
    void createsUnique32HexSessionId() {
        String id1 = Session.createSession();
        String id2 = Session.createSession();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        assertEquals(32, id1.length());
        assertTrue(id1.matches("^[0-9a-f]{32}$"));
    }

    @Test
    void setsAndGetsAttributes() {
        String id = Session.createSession();
        Session.setAttribute(id, "username", "alice");
        Session.setAttribute(id, "role", "admin");

        Map<String, Object> data = Session.getSession(id);
        assertNotNull(data);
        assertEquals("alice", data.get("username"));
        assertEquals("admin", data.get("role"));
    }

    @Test
    void destroysSession() {
        String id = Session.createSession();
        Session.setAttribute(id, "temp", "val");
        assertNotNull(Session.getSession(id));

        Session.destroySession(id);
        assertNull(Session.getSession(id));
    }

    @Test
    void unknownSessionReturnsNull() {
        assertNull(Session.getSession("nonexistent-id"));
        assertNull(Session.getSession(null));
        assertFalse(Session.setAttribute("nonexistent-id", "k", "v"));
    }

    @Test
    void sessionSweepDoesNotCrash() {
        Session.createSession();
        Session.sweep();
        assertTrue(Session.count() >= 1);
    }
}
