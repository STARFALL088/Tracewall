package tracewall.privacy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyPolicyTest {
    @Test void freshConsentIsOffAndPairingDoesNotNeedHistoryOrMetadata() {
        PrivacyPolicy fresh = PrivacyPolicy.defaults();
        assertFalse(fresh.allowsCloud());
        assertFalse(fresh.history); assertFalse(fresh.inventory);
        assertEquals(PrivacyPolicy.DENIED, fresh.historyTicket());
        PrivacyPolicy paired = fresh.withCloud(true);
        assertTrue(paired.acceptsCloud(paired.generation));
        assertFalse(paired.acceptsHistory(paired.generation));
        assertFalse(paired.acceptsInventory(paired.generation, true));
        assertTrue(paired.acceptsInventory(paired.generation, false)); // Opt-out cleanup, not upload.
    }
    @Test void historyAndInventoryAreIndependentAndLocalAlwaysOverridesBoth() {
        PrivacyPolicy cloud = PrivacyPolicy.defaults().withCloud(true);
        PrivacyPolicy queries = cloud.withHistory(true);
        assertTrue(queries.acceptsHistory(queries.historyTicket()));
        assertFalse(queries.acceptsInventory(queries.generation, true));
        PrivacyPolicy names = cloud.withInventory(true);
        assertTrue(names.acceptsInventory(names.generation, true));
        assertFalse(names.acceptsHistory(names.generation));
        PrivacyPolicy local = queries.withInventory(true).withCloud(false);
        assertTrue(local.history); assertTrue(local.inventory); // Choices survive, effective grants do not.
        assertFalse(local.acceptsCloud(local.generation));
        assertEquals(PrivacyPolicy.DENIED, local.historyTicket());
    }
    @Test void enablingNeverBackfillsAnEarlierOrDeniedQueryTicket() {
        PrivacyPolicy local = PrivacyPolicy.defaults();
        long before = local.historyTicket();
        PrivacyPolicy sharing = local.withCloud(true).withHistory(true);
        assertFalse(sharing.acceptsHistory(before));
        long issued = sharing.historyTicket();
        assertTrue(sharing.acceptsHistory(issued));
        PrivacyPolicy again = sharing.withHistory(false).withHistory(true);
        assertFalse(again.acceptsHistory(issued)); // ABA opt-out/re-enable.
        assertTrue(again.acceptsHistory(again.historyTicket()));
    }
    @Test void oldSignInListenerAndMetadataCallbacksCannotSurviveModeOrConsentChanges() {
        PrivacyPolicy sharing = new PrivacyPolicy(true, true, true, false);
        long issued = sharing.generation;
        PrivacyPolicy optedOut = sharing.withCloud(false).withCloud(true);
        assertFalse(optedOut.acceptsCloud(issued));
        assertFalse(optedOut.acceptsInventory(issued, true));
        PrivacyPolicy namesAgain = sharing.withInventory(false).withInventory(true);
        assertFalse(namesAgain.acceptsInventory(issued, true));
    }
    @Test void approvedDeletionStopsEverythingAndRequiresVerificationBeforeReconnect() {
        PrivacyPolicy cloud = new PrivacyPolicy(true, true, true, false);
        PrivacyPolicy pending = cloud.beginDeletion();
        assertTrue(pending.deletionPending); assertFalse(pending.cloud);
        assertFalse(pending.history); assertFalse(pending.inventory);
        assertFalse(pending.acceptsCloud(cloud.generation));
        assertThrows(IllegalStateException.class, () -> pending.withCloud(true));
        assertThrows(IllegalStateException.class, () -> pending.withHistory(true));
        assertThrows(IllegalStateException.class, () -> pending.withInventory(true));
        PrivacyPolicy complete = pending.finishDeletion();
        assertFalse(complete.deletionPending); assertFalse(complete.allowsCloud());
        assertFalse(complete.history); assertFalse(complete.inventory);
        assertThrows(IllegalStateException.class, complete::finishDeletion);
    }
    @Test void restoredPendingDeletionCannotStartCloudEvenWithAnOldCloudPreference() {
        PrivacyPolicy restored = new PrivacyPolicy(true, true, true, true);
        assertFalse(restored.allowsCloud());
        assertFalse(restored.acceptsInventory(0, true));
        assertFalse(restored.acceptsHistory(0));
    }
    @Test void idempotentSettingsDoNotInvalidateCurrentWork() {
        PrivacyPolicy sharing = new PrivacyPolicy(true, true, true, false);
        assertSame(sharing, sharing.withCloud(true));
        assertSame(sharing, sharing.withHistory(true));
        assertSame(sharing, sharing.withInventory(true));
    }
}
