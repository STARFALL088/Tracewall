package tracewall.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuthFlowGuardTest {
    @Test public void doubleSubmitIsRejected() {
        AuthFlowGuard guard = new AuthFlowGuard();
        long token = guard.begin();
        assertTrue(token > 0);
        assertTrue(guard.busy());
        assertEquals(0L, guard.begin());
        assertTrue(guard.owns(token));
    }

    @Test public void duplicateCompletionCannotFinishNewerWork() {
        AuthFlowGuard guard = new AuthFlowGuard();
        long old = guard.begin();
        assertTrue(guard.finish(old));
        long newer = guard.begin();
        assertNotEquals(old, newer);
        assertFalse(guard.finish(old));
        assertTrue(guard.busy());
        assertTrue(guard.owns(newer));
        assertTrue(guard.finish(newer));
        assertFalse(guard.busy());
    }

    @Test public void cancellationInvalidatesStaleGoogleCallback() {
        AuthFlowGuard guard = new AuthFlowGuard();
        long chooser = guard.begin();
        assertTrue(guard.finish(chooser));
        assertFalse(guard.owns(chooser));
        assertFalse(guard.finish(chooser));
        assertFalse(guard.owns(0));
        assertTrue(guard.begin() > chooser);
    }
}
