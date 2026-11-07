package tracewall.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DnsMetricsTest {

    @Test void countersSeparateDecisionsFailuresAndDrops() {
        DnsMetrics metrics = new DnsMetrics();
        metrics.recordDecision(true, false);
        metrics.recordDecision(false, true);
        metrics.recordDecision(false, false);
        metrics.recordServfail();
        metrics.recordQueueDropped();
        metrics.recordHttpsBlocked();

        DnsMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.allowed);
        assertEquals(1, snapshot.blocked);
        assertEquals(1, snapshot.servfail);
        assertEquals(1, snapshot.queueDropped);
        assertEquals(1, snapshot.unknownUid);
        assertEquals(1, snapshot.httpsBlocked);
    }

    @Test void resetClearsEverySessionCounter() {
        DnsMetrics metrics = new DnsMetrics();
        metrics.recordDecision(true, true);
        metrics.recordServfail();
        metrics.recordQueueDropped();
        metrics.recordHttpsBlocked();
        metrics.reset();

        DnsMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(0, snapshot.allowed);
        assertEquals(0, snapshot.blocked);
        assertEquals(0, snapshot.servfail);
        assertEquals(0, snapshot.queueDropped);
        assertEquals(0, snapshot.unknownUid);
        assertEquals(0, snapshot.httpsBlocked);
    }
}
