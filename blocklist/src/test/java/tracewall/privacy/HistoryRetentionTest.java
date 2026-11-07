package tracewall.privacy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryRetentionTest {
    @Test void onlyExplicitBoundedWindowsAreAccepted() {
        for (int days : new int[] {0, 1, 3, 7}) assertTrue(HistoryRetention.valid(days));
        for (int days : new int[] {-1, 2, 4, 8, Integer.MAX_VALUE}) {
            assertFalse(HistoryRetention.valid(days));
            assertEquals(7, HistoryRetention.fromStored(days));
            assertThrows(IllegalArgumentException.class, () -> HistoryRetention.cutoff(days, 0));
        }
    }
    @Test void exactAgeBoundaryIsInclusiveAndEarlierRowsArePruned() {
        long now = 20L * 86_400_000;
        for (int days : new int[] {1, 3, 7}) {
            long cutoff = now - days * 86_400_000L;
            assertEquals(cutoff, HistoryRetention.cutoff(days, now));
            assertTrue(HistoryRetention.retain(days, cutoff, now));
            assertFalse(HistoryRetention.retain(days, cutoff - 1, now));
        }
    }
    @Test void zeroMeansNoPersistenceEvenForFreshRowsAndNotNoLiveViewOrCloud() {
        assertFalse(HistoryRetention.retain(0, 0, 0));
        assertFalse(HistoryRetention.retain(0, 123, 123));
        PrivacyPolicy explicitCloud = PrivacyPolicy.defaults().withCloud(true).withHistory(true);
        assertTrue(explicitCloud.acceptsHistory(explicitCloud.historyTicket()));
    }
    @Test void cutoffClampsBeforeEpochAndDoesNotOverflowAtLongMax() {
        assertEquals(0, HistoryRetention.cutoff(7, 1));
        assertTrue(HistoryRetention.retain(7, 0, 1));
        assertFalse(HistoryRetention.retain(7, -1, 1));
        assertEquals(Long.MAX_VALUE - 7L * 86_400_000, HistoryRetention.cutoff(7, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> HistoryRetention.cutoff(7, -1));
    }
}
