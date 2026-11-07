package tracewall.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryBufferTest {
    @Test void queueCapacityIsFixedAndOverflowIsCumulative() throws Exception {
        HistoryBuffer<String> buffer = new HistoryBuffer<>(2);
        assertTrue(buffer.offer("one", true, -1)); assertTrue(buffer.offer("two", true, -1));
        assertFalse(buffer.offer("three", true, -1)); assertEquals(1, buffer.droppedRowCount());
        buffer.pollBatch(2, 0, TimeUnit.MILLISECONDS);
        assertTrue(buffer.offer("four", true, -1)); assertEquals(1, buffer.droppedRowCount());
        buffer.recordFailedRows(2); assertEquals(3, buffer.droppedRowCount());
    }
    @Test void drainedPreClearBatchCannotReappearAfterClear() throws Exception {
        HistoryBuffer<String> buffer = new HistoryBuffer<>(4);
        List<String> stored = new ArrayList<>();
        buffer.offer("drained", true, 9);
        List<HistoryBuffer.Row<String>> old = buffer.pollBatch(4, 0, TimeUnit.MILLISECONDS);
        buffer.offer("still queued", true, 9);
        buffer.invalidateAndRun(() -> { stored.clear(); return null; });
        buffer.writeCurrent(old, rows -> { for (HistoryBuffer.Row<String> row : rows) stored.add(row.value); });
        assertTrue(stored.isEmpty());
        assertTrue(buffer.pollBatch(4, 0, TimeUnit.MILLISECONDS).isEmpty());
        buffer.offer("post-clear", true, 9);
        buffer.writeCurrent(buffer.pollBatch(4, 0, TimeUnit.MILLISECONDS), rows -> stored.add(rows.get(0).value));
        assertEquals(List.of("post-clear"), stored);
    }
    @Test void clearWaitsForInflightInsertThenErasesItWhileOffersNeverWaitForStorage() throws Exception {
        HistoryBuffer<String> buffer = new HistoryBuffer<>(4);
        List<String> stored = new ArrayList<>();
        CountDownLatch insertEntered = new CountDownLatch(1), releaseInsert = new CountDownLatch(1), clearRequested = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            buffer.offer("inflight", true, 4);
            List<HistoryBuffer.Row<String>> batch = buffer.pollBatch(4, 0, TimeUnit.MILLISECONDS);
            Future<?> insertion = workers.submit(() -> buffer.writeCurrent(batch, rows -> {
                insertEntered.countDown();
                try { assertTrue(releaseInsert.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                stored.add(rows.get(0).value);
            }));
            assertTrue(insertEntered.await(5, TimeUnit.SECONDS));
            assertTrue(buffer.offer("queued while disk busy", true, 4)); // Completes while insert monitor is held.
            Future<?> clear = workers.submit(() -> {
                clearRequested.countDown();
                buffer.invalidateAndRun(() -> { stored.clear(); return null; });
            });
            assertTrue(clearRequested.await(5, TimeUnit.SECONDS));
            releaseInsert.countDown();
            insertion.get(5, TimeUnit.SECONDS); clear.get(5, TimeUnit.SECONDS);
            assertTrue(stored.isEmpty());
            assertTrue(buffer.pollBatch(4, 0, TimeUnit.MILLISECONDS).isEmpty());
        } finally { releaseInsert.countDown(); workers.shutdownNow(); }
    }
    @Test void retentionChangeInvalidatesDrainedRowsAndDoesNotInventUploadConsent() throws Exception {
        HistoryBuffer<String> buffer = new HistoryBuffer<>(4);
        buffer.offer("before change", true, PrivacyPolicy.DENIED);
        List<HistoryBuffer.Row<String>> old = buffer.pollBatch(4, 0, TimeUnit.MILLISECONDS);
        buffer.invalidateAndRun(() -> null);
        buffer.writeCurrent(old, rows -> fail("Stale retention generation must be discarded"));
        buffer.offer("cloud only", false, 7);
        buffer.writeCurrent(buffer.pollBatch(4, 0, TimeUnit.MILLISECONDS), rows -> {
            assertFalse(rows.get(0).persist); assertEquals(7, rows.get(0).uploadTicket);
        });
        assertEquals(0, buffer.droppedRowCount()); // Intentional privacy changes are not overload loss.
    }
    @Test void storageFailuresPropagateAndCannotResurrectAnInvalidatedBatch() throws Exception {
        HistoryBuffer<String> buffer = new HistoryBuffer<>(1);
        buffer.offer("old", true, -1);
        List<HistoryBuffer.Row<String>> old = buffer.pollBatch(1, 0, TimeUnit.MILLISECONDS);
        assertThrows(IllegalStateException.class, () -> buffer.invalidateAndRun(() -> { throw new IllegalStateException(); }));
        buffer.writeCurrent(old, rows -> fail("Clear invalidates old queued work even if storage deletion fails"));
        assertThrows(IllegalArgumentException.class, () -> buffer.pollBatch(0, 0, TimeUnit.MILLISECONDS));
    }
}
