package tracewall.child.log;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.List;

/**
 * History table access. Deliberately tiny: insert in batches, read a page,
 * prune old rows. Anything more belongs in a view model, not the DAO.
 */
@Dao
public interface QueryDao {

    @Insert
    long insert(QueryRecord record);

    @Insert
    void insertAll(List<QueryRecord> records);

    @Query("SELECT * FROM queries ORDER BY timeMs DESC LIMIT :limit")
    List<QueryRecord> recent(int limit);

    /** Freeze inserts (including late/backdated rows) until the next explicit refresh. */
    @Query("SELECT COALESCE(MAX(id), 0) FROM queries")
    long historySnapshotId();

    /** Only pass bound SQL produced by tracewall.data.HistoryQuery. */
    @RawQuery
    List<QueryRecord> historyPage(SupportSQLiteQuery query);

    @RawQuery
    HistoryStats historyStatistics(SupportSQLiteQuery query);

    /** Aggregates over the complete selected snapshot, not a limited page. */
    class HistoryStats {
        public long total;
        public long blocked;
        public double averageLatencyMs;
    }

    /** Retention: the service calls this hourly with a cutoff. */
    @Query("DELETE FROM queries WHERE timeMs < :cutoffMs")
    int prune(long cutoffMs);

    /** User-requested clear, reached through the provider's delete(). */
    @Query("DELETE FROM queries")
    int clear();
}
