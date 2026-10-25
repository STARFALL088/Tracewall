package tracewall.child.log;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tracewall.child.sync.FirebaseSync;

/**
 * Batched writer from the packet path into Room.
 *
 * One insert per lookup would mean a disk transaction per DNS query, which is
 * both slow and pointless: the query log is not a ledger, it is history. A
 * bounded queue plus a 1.5 s flush turns a burst of 200 lookups into a single
 * transaction and keeps the worker thread off the tunnel thread entirely.
 *
 * The queue is bounded on purpose. If the disk cannot keep up, the newest row
 * is dropped rather than blocking the resolver - the live ring still shows it,
 * so nothing user-visible is lost.
 */
public final class HistoryWriter {

    private static final String TAG = "Tracewall";
    private static final int MAX_QUEUE = 2000;
    private static final long FLUSH_MS = 1500;
    private static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000; // one week
    private static final long PRUNE_EVERY_MS = 60L * 60 * 1000;

    private static final HistoryWriter INSTANCE = new HistoryWriter();

    public static HistoryWriter get() {
        return INSTANCE;
    }

    private final BlockingQueue<QueryRecord> queue = new LinkedBlockingQueue<>(
        MAX_QUEUE
    );

    private volatile boolean started;
    private volatile TracewallDb db;
    private volatile long lastPrune;

    private HistoryWriter() {}

    public void start(Context ctx) {
        if (started) return;
        db = TracewallDb.get(ctx);
        started = true;
        Thread t = new Thread(this::loop, "tracewall-history");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "history writer started");
    }

    /** Never blocks the caller and never throws. */
    public void offer(QueryRecord record) {
        if (!started || record == null) return;
        queue.offer(record); // false = queue full = drop this row
    }

    private void loop() {
        List<QueryRecord> batch = new ArrayList<>(256);
        while (true) {
            try {
                QueryRecord first = queue.poll(FLUSH_MS, TimeUnit.MILLISECONDS);
                if (first != null) batch.add(first);
                queue.drainTo(batch, 255);
                if (!batch.isEmpty()) {
                    db.queries().insertAll(batch);
                    // Mirror to Firestore so the parent app can read history
                    // remotely. FirebaseSync queues writes offline when there
                    // is no network and flushes them automatically on reconnect.
                    FirebaseSync sync = FirebaseSync.get();
                    for (QueryRecord r : batch) {
                        sync.logQuery(
                            r.timeMs,
                            r.host,
                            r.blocked,
                            r.uid,
                            r.policy
                        );
                    }
                    batch.clear();
                }
                maybePrune();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A full disk or a corrupted db must not kill the writer loop:
                // drop the batch, say so, keep serving the live view.
                Log.w(TAG, "history write failed: " + e.getMessage());
                batch.clear();
            }
        }
    }

    private void maybePrune() {
        long now = System.currentTimeMillis();
        if (now - lastPrune < PRUNE_EVERY_MS) return;
        lastPrune = now;
        try {
            int removed = db.queries().prune(now - RETENTION_MS);
            if (removed > 0) Log.i(
                TAG,
                "pruned " + removed + " rows past retention"
            );
        } catch (RuntimeException e) {
            Log.w(TAG, "prune failed: " + e.getMessage());
        }
    }
}
