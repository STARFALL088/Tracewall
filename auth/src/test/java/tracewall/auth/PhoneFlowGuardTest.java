package tracewall.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneFlowGuardTest {
    @Test public void cooldownUsesMonotonicTimeWithoutSleeping() {
        PhoneFlowGuard guard = new PhoneFlowGuard();
        assertEquals(0L, guard.remainingSeconds(100));
        long token = guard.start(100);
        assertTrue(token > 0);
        assertEquals(60L, guard.remainingSeconds(100));
        assertEquals(1L, guard.remainingSeconds(60_099));
        assertEquals(0L, guard.remainingSeconds(60_100));
        assertEquals(0L, guard.start(60_099));
        assertTrue(guard.start(60_100) > token);
    }

    @Test public void actualCodeSentExtendsButReattachmentDoesNotResetCooldown() {
        PhoneFlowGuard guard = new PhoneFlowGuard();
        long token = guard.start(100);
        guard.sent(token, 3_100);
        assertEquals(60L, guard.remainingSeconds(3_100));
        guard.sent(token, 30_100); // Callback reattachment must not keep pushing out resend.
        assertEquals(33L, guard.remainingSeconds(30_100));
    }

    @Test public void cancellingOrChangingPhoneDoesNotBypassCooldown() {
        PhoneFlowGuard guard = new PhoneFlowGuard();
        long token = guard.start(100);
        guard.cancel();
        assertFalse(guard.active());
        assertFalse(guard.owns(token));
        assertEquals(0L, guard.start(200));
        assertEquals(60L, guard.remainingSeconds(200));
        assertTrue(guard.start(60_100) > token);
    }

    @Test public void reattachIsBoundedBeforeOriginalDeadlineAndNotAfterEarlyTimeout() {
        PhoneFlowGuard guard = new PhoneFlowGuard();
        long token = guard.start(100);
        assertTrue(guard.canReattach(token, 1_000));
        assertFalse(guard.canReattach(token, 58_100));
        assertFalse(guard.canReattach(token, 60_100));
        guard.stopListening(token);
        assertFalse(guard.canReattach(token, 1_001));
    }

    @Test public void autoAndManualCompletionCannotBothClaimCredential() {
        PhoneFlowGuard guard = new PhoneFlowGuard();
        long token = guard.start(100);
        assertTrue(guard.claimCompletion(token, false));
        assertFalse(guard.claimCompletion(token, true));
        assertFalse(guard.claimCompletion(token, false));
        assertFalse(guard.canReattach(token, 200));
        guard.cancel();
        assertFalse(guard.claimCompletion(token, true));
    }

    @Test public void invalidManualCodeCanRetryButAutoCredentialIsConsumedOnce() {
        PhoneFlowGuard guard = new PhoneFlowGuard();
        long token = guard.start(100);
        assertTrue(guard.claimCompletion(token, true));
        guard.completionFailed(token);
        assertFalse(guard.claimCompletion(token, true));
        assertTrue(guard.claimCompletion(token, false));
        guard.completionFailed(token);
        assertTrue(guard.claimCompletion(token, false));
    }

    @Test public void staleCallbacksDoNotAffectResentGeneration() {
        PhoneFlowGuard guard = new PhoneFlowGuard();
        long old = guard.start(100);
        long newer = guard.start(60_100);
        assertFalse(guard.owns(old));
        assertFalse(guard.claimCompletion(old, true));
        guard.sent(old, 90_000);
        assertEquals(30L, guard.remainingSeconds(90_100));
        guard.stopListening(old);
        assertTrue(guard.canReattach(newer, 90_100));
    }
}
