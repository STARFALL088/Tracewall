package tracewall.net;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedWorkPoolTest {
    @Test void rejectsOverloadWithoutGrowingTheQueueOrRunningOnTheCaller() throws Exception {
        ThreadPoolExecutor pool = BoundedWorkPool.create("test-dns", 1, 1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            pool.execute(() -> {});
            assertEquals(1, pool.getQueue().size());
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> fail("Caller ran rejected work")));
            assertEquals(1, pool.getQueue().size());
            assertEquals(1, pool.getMaximumPoolSize());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test void validatesBoundsAndRejectsWorkAfterShutdown() {
        assertThrows(IllegalArgumentException.class, () -> BoundedWorkPool.create("dns", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundedWorkPool.create("dns", 1, 0));
        ThreadPoolExecutor pool = BoundedWorkPool.create("dns", 1, 1);
        pool.shutdownNow();
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> {}));
    }
}
