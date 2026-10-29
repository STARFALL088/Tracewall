package tracewall.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LatestWorkTest {
    @Test void optOutWaitsForAnInflightUploadAndReplacesOlderQueuedRefreshes() {
        LatestWork<Object> work = new LatestWork<>();
        Object upload = new Object(), refresh = new Object(), optOut = new Object();
        assertSame(upload, work.offer(upload));
        assertNull(work.offer(refresh));
        assertNull(work.offer(optOut));
        assertSame(optOut, work.finish(upload, false));
        assertNull(work.finish(optOut, false));
    }
    @Test void aNewerEnableWinsOverRepeatedCleanupAndCanQueueWhileCleanupRuns() {
        LatestWork<Object> work = new LatestWork<>();
        Object cleanup = new Object(), enable = new Object(), newer = new Object();
        assertSame(cleanup, work.offer(cleanup));
        assertNull(work.offer(enable));
        assertSame(enable, work.finish(cleanup, true));
        assertNull(work.offer(newer));
        assertSame(newer, work.finish(enable, false));
        assertNull(work.finish(newer, false));
    }
    @Test void repeatAndIdleRestartRetainSingleFlightOwnership() {
        LatestWork<Object> work = new LatestWork<>();
        Object first = new Object(), second = new Object();
        assertSame(first, work.offer(first));
        assertSame(first, work.finish(first, true));
        assertNull(work.finish(first, false));
        assertSame(second, work.offer(second));
        assertNull(work.finish(second, false));
    }
    @Test void staleDoubleCompletionAndNullRequestsCannotStartAnotherWriter() {
        LatestWork<Object> work = new LatestWork<>();
        Object first = new Object(), second = new Object();
        assertThrows(NullPointerException.class, () -> work.offer(null));
        assertSame(first, work.offer(first));
        work.offer(second);
        assertSame(second, work.finish(first, false));
        assertThrows(IllegalStateException.class, () -> work.finish(first, false));
        assertNull(work.finish(second, false));
        assertThrows(IllegalStateException.class, () -> work.finish(second, false));
    }
}
