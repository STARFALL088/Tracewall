package tracewall.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tracewall.security.PairingPlan.Action;

class PairingPlanTest {
    @Test void newParentRegistersBothStores() {
        assertEquals(Action.REGISTER_BOTH, PairingPlan.next(null, null, "ABCDEFGH"));
    }
    @Test void completedParentReconnectsWithoutRewritingMembership() {
        assertEquals(Action.COMPLETE, PairingPlan.next("ABCDEFGH", "ABCDEFGH", "BCDEFGHJ"));
    }
    @Test void realtimeOnlyRetryCompletesMissingFirestoreClaim() {
        assertEquals(Action.REGISTER_FIRESTORE, PairingPlan.next("ABCDEFGH", null, "ABCDEFGH"));
    }
    @Test void firestoreOnlyRetryCompletesMissingRealtimeClaim() {
        assertEquals(Action.REGISTER_REALTIME, PairingPlan.next(null, "ABCDEFGH", "ABCDEFGH"));
    }
    @Test void differentCodeDoesNotOverwritePartiallyRegisteredMembership() {
        assertEquals(Action.CHILD_RESET_REQUIRED, PairingPlan.next("ABCDEFGH", null, "BCDEFGHJ"));
        assertEquals(Action.CHILD_RESET_REQUIRED, PairingPlan.next(null, "ABCDEFGH", "BCDEFGHJ"));
    }
}
