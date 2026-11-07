package tracewall.child.log;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tracewall.child.sync.FirebaseSync;
import tracewall.privacy.HistoryBuffer;
import tracewall.privacy.HistoryRetention;
import tracewall.privacy.PrivacyPolicy;

/** Batched Room writer. Offers never wait for storage, a clear, a setting change or Firebase. */
public final class HistoryWriter {
    private static final String TAG = "Tracewall";
    private static final String PREFS = "tracewall.privacy", RETENTION = "retention_days";
    private static final long FLUSH_MS = 1500, PRUNE_EVERY_MS = 60_000;
    private static final HistoryWriter INSTANCE = new HistoryWriter();
    private final HistoryBuffer<QueryRecord> buffer = new HistoryBuffer<>(2000);
    private volatile boolean started;
    private volatile TracewallDb db;
    private volatile int retentionDays = HistoryRetention.DEFAULT_DAYS;
    private long lastPrune;
    private HistoryWriter() {}
    public static HistoryWriter get() { return INSTANCE; }

    public synchronized void start(Context context) {
        if (started) return;
        db = TracewallDb.get(context);
        retentionDays = retentionDays(context);
        started = true;
        Thread thread = new Thread(this::loop, "tracewall-history");
        thread.setDaemon(true);
        thread.start();
    }
    public int retentionDays(Context context) {
        return HistoryRetention.fromStored(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(RETENTION, HistoryRetention.DEFAULT_DAYS));
    }
    /** The packet path takes only an immutable consent snapshot and a bounded queue offer. */
    public void offer(QueryRecord record) {
        if (!started || record == null) return;
        long uploadTicket = FirebaseSync.get().captureHistoryTicket();
        boolean persist = retentionDays > 0;
        if (persist || uploadTicket != PrivacyPolicy.DENIED) buffer.offer(record, persist, uploadTicket);
    }
    /** Cumulative process-lifetime overflow/storage-failure count; intentional privacy discards are not failures. */
    public long droppedRowCount() { return buffer.droppedRowCount(); }

    /** Call off-main-thread and only after PinGate.require. Shares the insertion monitor, including provider clears. */
    public int clearStored(Context context) {
        TracewallDb target = TracewallDb.get(context);
        return buffer.invalidateAndRun(() -> target.queries().clear());
    }
    /** Changes take effect before pruning. A failed prune is a failure, retried by the writer (not a success toast). */
    public void setRetentionDays(Context context, int days) {
        if (!HistoryRetention.valid(days)) throw new IllegalArgumentException("Invalid retention");
        TracewallDb target = TracewallDb.get(context);
        buffer.invalidateAndRun(() -> {
            if (!context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(RETENTION, days).commit()) throw new IllegalStateException("Retention could not be saved");
            retentionDays = days;
            lastPrune = 0;
            if (days == 0) target.queries().clear();
            else target.queries().prune(HistoryRetention.cutoff(days, System.currentTimeMillis()));
            return null;
        });
    }
    private void loop() {
        while (true) {
            List<HistoryBuffer.Row<QueryRecord>> batch = null;
            try {
                batch = buffer.pollBatch(256, FLUSH_MS, TimeUnit.MILLISECONDS);
                maybePrune();
                buffer.writeCurrent(batch, current -> {
                    long now = System.currentTimeMillis();
                    List<QueryRecord> durable = new ArrayList<>(current.size());
                    for (HistoryBuffer.Row<QueryRecord> row : current)
                        if (row.persist && HistoryRetention.retain(retentionDays, row.value.timeMs, now)) durable.add(row.value);
                    // A clear cannot interleave between the epoch check and this insert.
                    if (!durable.isEmpty()) db.queries().insertAll(durable);
                    FirebaseSync sync = FirebaseSync.get();
                    // Independent of Room persistence. The ticket was captured BEFORE the asynchronous flush.
                    for (HistoryBuffer.Row<QueryRecord> row : current) {
                        QueryRecord r = row.value;
                        sync.logQuery(row.uploadTicket, r.timeMs, r.host, r.blocked, r.uid, r.policy);
                    }
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                if (batch != null) buffer.recordFailedRows(batch.size());
                Log.w(TAG, "History write failed; packet processing continues", error);
            }
        }
    }
    private void maybePrune() {
        buffer.storageAction(() -> {
            long now = System.currentTimeMillis();
            if (lastPrune != 0 && now >= lastPrune && now - lastPrune < PRUNE_EVERY_MS) return;
            // Leave lastPrune untouched on failure so the next loop retries.
            if (retentionDays == 0) db.queries().clear();
            else db.queries().prune(HistoryRetention.cutoff(retentionDays, now));
            lastPrune = now;
        });
    }
}
