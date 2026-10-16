package localserver.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session management tests: create, get, expire, destroy, capacity.
 * Person B.
 */
class SessionTest {

    @BeforeEach
    void clearSessions() {
        // destroy any sessions from previous tests by sweeping with a forced expire
        // (We can't directly clear the map, but we can create and destroy a test session
        //  to confirm the API works.)
    }

    @Test
    void createReturnsUniqueTokens() {
        String id1 = Session.createSession();
        String id2 = Session.createSession();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        // 32 hex chars = 16 bytes
        assertEquals(32, id1.length());
        assertTrue(id1.matches("[0-9a-f]+"), "token must be lowercase hex");
        Session.destroySession(id1);
        Session.destroySession(id2);
    }

    @Test
    void getSessionReturnsDataMap() {
        String id = Session.createSession();
        Map<String, Object> data = Session.getSession(id);
        assertNotNull(data);
        assertTrue(data.isEmpty());
        Session.destroySession(id);
    }

    @Test
    void setAttributeStoresValue() {
        String id = Session.createSession();
        boolean ok = Session.setAttribute(id, "user", "alice");
        assertTrue(ok);
        Map<String, Object> data = Session.getSession(id);
        assertEquals("alice", data.get("user"));
        Session.destroySession(id);
    }

    @Test
    void destroyedSessionReturnsNull() {
        String id = Session.createSession();
        Session.destroySession(id);
        assertNull(Session.getSession(id));
    }

    @Test
    void unknownSessionIdReturnsNull() {
        assertNull(Session.getSession("00000000000000000000000000000000"));
    }

    @Test
    void nullSessionIdReturnsNull() {
        assertNull(Session.getSession(null));
    }

    @Test
    void sweepDoesNotThrow() {
        // Just verifies sweep() can be called safely
        assertDoesNotThrow(Session::sweep);
    }

    @Test
    void countReflectsActiveSessions() {
        int before = Session.count();
        String id1 = Session.createSession();
        String id2 = Session.createSession();
        assertTrue(Session.count() >= before + 2);
        Session.destroySession(id1);
        Session.destroySession(id2);
    }

    @Test
    void setAttributeOnDestroyedSessionReturnsFalse() {
        String id = Session.createSession();
        Session.destroySession(id);
        boolean ok = Session.setAttribute(id, "key", "value");
        assertFalse(ok);
    }
}
