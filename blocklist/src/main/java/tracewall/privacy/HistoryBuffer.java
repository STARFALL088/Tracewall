package tracewall.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded nonblocking offers; storage writes and generation-changing clears share one monitor. */
public final class HistoryBuffer<T> {
    public static final class Row<T> {
        public final T value;
        public final boolean persist;
        public final long uploadTicket;
        private final long epoch;
        private Row(T value, boolean persist, long uploadTicket, long epoch) {
            this.value = value; this.persist = persist; this.uploadTicket = uploadTicket; this.epoch = epoch;
        }
    }
    private final ArrayBlockingQueue<Row<T>> queue;
    private final AtomicLong epoch = new AtomicLong(), dropped = new AtomicLong();
    private final Object storage = new Object();
    public HistoryBuffer(int capacity) { queue = new ArrayBlockingQueue<>(capacity); }
    /** Never waits for storage, clear, retention changes or Firebase. */
    public boolean offer(T value, boolean persist, long uploadTicket) {
        long ticket = epoch.get();
        if (value == null) return false;
        boolean accepted = queue.offer(new Row<>(value, persist, uploadTicket, ticket));
        if (!accepted) dropped.incrementAndGet();
        return accepted;
    }
    public List<Row<T>> pollBatch(int maximum, long timeout, TimeUnit unit) throws InterruptedException {
        if (maximum < 1) throw new IllegalArgumentException("Invalid batch size");
        List<Row<T>> rows = new ArrayList<>(maximum);
        Row<T> first = queue.poll(timeout, unit);
        if (first != null) rows.add(first);
        queue.drainTo(rows, maximum - rows.size());
        return rows;
    }
    /** The callback (including the insert) must finish under this monitor, not schedule a later insert. */
    public void writeCurrent(List<Row<T>> rows, Consumer<List<Row<T>>> write) {
        synchronized (storage) {
            long current = epoch.get();
            List<Row<T>> accepted = new ArrayList<>(rows.size());
            for (Row<T> row : rows) if (row.epoch == current) accepted.add(row);
            if (!accepted.isEmpty()) write.accept(accepted);
        }
    }
    /** An already-running insert finishes before deletion; drained and concurrently enqueued old rows are stale. */
    public <R> R invalidateAndRun(Supplier<R> action) {
        synchronized (storage) {
            epoch.incrementAndGet();
            queue.clear();
            return action.get();
        }
    }
    public void storageAction(Runnable action) { synchronized (storage) { action.run(); } }
    public long droppedRowCount() { return dropped.get(); }
    public void recordFailedRows(int count) { if (count > 0) dropped.addAndGet(count); }
}
