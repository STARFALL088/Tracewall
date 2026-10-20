package tracewall.child.log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory ring of recent DNS queries.
 *
 * Deliberately not Room yet: the packet loop writes from worker threads at
 * lookup rate, and a database write per lookup would dominate the latency we
 * are trying to keep honest. This is the live view the UI reads; the durable
 * history lands on top of it later.
 *
 * Every method is synchronized because the writer is a pool thread and the
 * reader is the main thread. The ring is small (500) and entries are
 * immutable, so contention is irrelevant at DNS rates.
 */
public final class QueryLog {

    /** One answered lookup. Immutable so it can cross threads unguarded. */
    public static final class Entry {
        public final long timeMs;
        public final String host;
        public final int type;
        public final boolean blocked;
        public final int srcPort;
        public final int uid;              // owner of the querying socket, -1 if unknown
        public final String policy;        // why: category name, "app", "allowlist", ""
        public final long latencyMs;
        public final String resolver;

        public Entry(long timeMs, String host, int type, boolean blocked,
                     int srcPort, int uid, String policy, long latencyMs,
                     String resolver) {
            this.timeMs = timeMs;
            this.host = host;
            this.type = type;
            this.blocked = blocked;
            this.srcPort = srcPort;
            this.uid = uid;
            this.policy = policy == null ? "" : policy;
            this.latencyMs = latencyMs;
            this.resolver = resolver;
        }

        /** The durable form of this entry, for the Room writer. */
        public QueryRecord toRecord() {
            QueryRecord r = new QueryRecord();
            r.timeMs = timeMs;
            r.host = host;
            r.type = type;
            r.blocked = blocked;
            r.uid = uid;
            r.policy = policy;
            r.latencyMs = latencyMs;
            r.resolver = resolver;
            return r;
        }

        /** One line for the plain-text log view. */
        public String line() {
            String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timeMs));
            return t + "  " + (blocked ? "BLOCK" : "allow") + "  "
                    + tracewall.net.DnsMessage.typeName(type) + "  " + host
                    + "  " + latencyMs + "ms";
        }
    }

    /** Counters for the header. */
    public static final class Stats {
        public final int total;
        public final int blocked;
        public final int retained;

        Stats(int total, int blocked, int retained) {
            this.total = total;
            this.blocked = blocked;
            this.retained = retained;
        }
    }

    private static final int CAPACITY = 500;
    private static final QueryLog INSTANCE = new QueryLog();

    public static QueryLog get() { return INSTANCE; }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private int total;
    private int blocked;

    private QueryLog() { }

    public synchronized void add(Entry e) {
        if (e == null) return;
        total++;
        if (e.blocked) blocked++;
        entries.addFirst(e);              // newest first
        if (entries.size() > CAPACITY) entries.removeLast();
    }

    public synchronized List<Entry> snapshot(int limit) {
        int n = Math.min(limit, entries.size());
        List<Entry> out = new ArrayList<>(n);
        for (Entry e : entries) {
            if (out.size() >= n) break;
            out.add(e);
        }
        return out;
    }

    public synchronized Stats stats() {
        return new Stats(total, blocked, entries.size());
    }

    public synchronized void clear() {
        entries.clear();
        total = 0;
        blocked = 0;
    }
}
