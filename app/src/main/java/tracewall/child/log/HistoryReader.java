package tracewall.child.log;

import androidx.sqlite.db.SimpleSQLiteQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

import tracewall.data.HistoryFilter;
import tracewall.data.HistoryQuery;

/** Worker-thread-only, bounded reads. No live-ring dependency and no schema changes. */
public final class HistoryReader {
    public static final int PAGE_SIZE = 100;
    private final TracewallDb db;

    public HistoryReader(TracewallDb db) { this.db = db; }

    public static final class Page {
        public final long snapshotId;
        public final List<QueryRecord> rows;
        public final QueryDao.HistoryStats stats;
        public final boolean hasNewer;
        public final boolean hasOlder;
        Page(long snapshotId, List<QueryRecord> rows, QueryDao.HistoryStats stats,
             boolean hasNewer, boolean hasOlder) {
            this.snapshotId = snapshotId;
            this.rows = Collections.unmodifiableList(rows);
            this.stats = stats;
            this.hasNewer = hasNewer;
            this.hasOlder = hasOlder;
        }
    }

    /** One short read transaction keeps the page, its buttons and statistics consistent. */
    public Page load(HistoryFilter filter, long snapshotId, HistoryQuery.Cursor anchor,
                     HistoryQuery.Direction direction) {
        checkCancelled();
        return db.runInTransaction(() -> {
            checkCancelled();
            long frozenId = snapshotId < 0 ? db.queries().historySnapshotId() : snapshotId;
            List<QueryRecord> rows = new ArrayList<>(rows(filter, frozenId, anchor, direction, PAGE_SIZE));
            if (direction == HistoryQuery.Direction.NEWER) Collections.reverse(rows);
            QueryDao.HistoryStats stats = statistics(filter, frozenId);
            boolean newer = !rows.isEmpty() && !rows(filter, frozenId, cursor(rows.get(0)),
                    HistoryQuery.Direction.NEWER, 1).isEmpty();
            boolean older = !rows.isEmpty() && !rows(filter, frozenId, cursor(rows.get(rows.size() - 1)),
                    HistoryQuery.Direction.OLDER, 1).isEmpty();
            checkCancelled();
            return new Page(frozenId, rows, stats, newer, older);
        });
    }

    public List<QueryRecord> rows(HistoryFilter filter, long snapshotId, HistoryQuery.Cursor anchor,
                                  HistoryQuery.Direction direction, int limit) {
        checkCancelled();
        HistoryQuery.Sql sql = HistoryQuery.page(filter, snapshotId, anchor, direction, limit);
        List<QueryRecord> rows = db.queries().historyPage(new SimpleSQLiteQuery(sql.text, sql.arguments()));
        checkCancelled();
        return rows;
    }

    public QueryDao.HistoryStats statistics(HistoryFilter filter, long snapshotId) {
        checkCancelled();
        HistoryQuery.Sql sql = HistoryQuery.statistics(filter, snapshotId);
        QueryDao.HistoryStats stats = db.queries().historyStatistics(new SimpleSQLiteQuery(sql.text, sql.arguments()));
        checkCancelled();
        return stats;
    }

    public static HistoryQuery.Cursor cursor(QueryRecord row) {
        return new HistoryQuery.Cursor(row.timeMs, row.id);
    }

    /** Room's synchronous SQL call may finish first; no next query or callback runs after cancellation. */
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
