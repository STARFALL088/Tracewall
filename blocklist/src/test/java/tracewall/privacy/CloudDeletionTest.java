package tracewall.privacy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudDeletionTest {
    @Test void batchesAndEmptyPagesAloneCannotMeanComplete() {
        CloudDeletion job = new CloudDeletion();
        assertEquals(CloudDeletion.Page.DELETE, job.page(200, false, false));
        assertEquals(CloudDeletion.Page.NEXT_COLLECTION, job.page(0, false, false));
        assertFalse(job.confirmed());
        assertTrue(job.verify(0, 0, false, false)); assertFalse(job.confirmed());
        assertTrue(job.verify(1, 0, false, false)); assertFalse(job.confirmed());
        assertTrue(job.verify(2, 0, false, false)); assertTrue(job.confirmed());
    }
    @Test void cachedOrPendingEmptySnapshotsAreNotProofOfDeletion() {
        for (boolean cached : new boolean[] {false, true}) for (boolean pending : new boolean[] {false, true}) {
            if (!cached && !pending) continue;
            CloudDeletion job = new CloudDeletion();
            assertEquals(CloudDeletion.Page.PENDING, job.page(0, cached, pending));
            assertFalse(job.verify(0, 0, false, false)); assertFalse(job.confirmed());
            CloudDeletion verify = new CloudDeletion();
            assertFalse(verify.verify(0, 0, cached, pending)); assertFalse(verify.confirmed());
        }
    }
    @Test void pageBudgetIsBoundedAndRetryRemainsNecessary() {
        CloudDeletion job = new CloudDeletion();
        for (int i = 0; i < CloudDeletion.MAX_BATCHES; i++)
            assertEquals(CloudDeletion.Page.DELETE, job.page(200, false, false));
        assertEquals(CloudDeletion.Page.PENDING, job.page(1, false, false));
        assertFalse(job.confirmed());
    }
    @Test void exactBudgetCanStillFinishIfNoDocumentsRemain() {
        CloudDeletion job = new CloudDeletion();
        for (int i = 0; i < CloudDeletion.MAX_BATCHES; i++) job.page(1, false, false);
        assertEquals(CloudDeletion.Page.NEXT_COLLECTION, job.page(0, false, false));
        for (int i = 0; i < 3; i++) assertTrue(job.verify(i, 0, false, false));
        assertTrue(job.confirmed());
    }
    @Test void duplicateOutOfOrderOrNonemptyVerificationFailsClosed() {
        CloudDeletion duplicate = new CloudDeletion();
        assertTrue(duplicate.verify(0, 0, false, false));
        assertFalse(duplicate.verify(0, 0, false, false));
        assertFalse(duplicate.confirmed());
        assertFalse(new CloudDeletion().verify(1, 0, false, false));
        assertFalse(new CloudDeletion().verify(0, 1, false, false));
    }
    @Test void malformedOrOversizedPagesFailClosed() {
        assertEquals(CloudDeletion.Page.PENDING, new CloudDeletion().page(-1, false, false));
        assertEquals(CloudDeletion.Page.PENDING, new CloudDeletion().page(201, false, false));
    }
}
