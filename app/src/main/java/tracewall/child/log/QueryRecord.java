package tracewall.child.log;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One durable history row.
 *
 * Public fields, no getters: Room maps them directly and this is a transfer
 * object, not a domain model. The live in-memory ring in {@link QueryLog}
 * stays the UI's fast path; this is what survives a restart.
 */
@Entity(tableName = "queries")
public class QueryRecord {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timeMs;
    public String host;
    public int type;
    public boolean blocked;
    public int uid;
    public String policy;
    public long latencyMs;
    public String resolver;

    public QueryRecord() { }
}
