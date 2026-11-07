package tracewall.data;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-lifetime DNS diagnostics. Lock-free because the packet workers update
 * it concurrently; the UI only takes a consistent-enough snapshot.
 *
 * <p>These are session counters, not durable analytics. Blocked policy
 * decisions, allowed forwarding attempts, upstream failures and dropped work
 * are tracked separately so a partial failure cannot look like a bypass.
 */
public final class DnsMetrics {

    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong allowed = new AtomicLong();
    private final AtomicLong servfail = new AtomicLong();
    private final AtomicLong queueDropped = new AtomicLong();
    private final AtomicLong unknownUid = new AtomicLong();
    private final AtomicLong httpsBlocked = new AtomicLong();

    public void reset() {
        blocked.set(0);
        allowed.set(0);
        servfail.set(0);
        queueDropped.set(0);
        unknownUid.set(0);
        httpsBlocked.set(0);
    }

    /** One ordinary query that reached a policy decision. */
    public void recordDecision(boolean wasBlocked, boolean attributionUnknown) {
        (wasBlocked ? blocked : allowed).incrementAndGet();
        if (attributionUnknown) unknownUid.incrementAndGet();
    }

    public void recordServfail() { servfail.incrementAndGet(); }
    public void recordQueueDropped() { queueDropped.incrementAndGet(); }
    public void recordHttpsBlocked() { httpsBlocked.incrementAndGet(); }

    public Snapshot snapshot() {
        // Read the dependent counters first so the snapshot cannot claim more
        // total decisions than the individual buckets it already observed.
        long unknown = unknownUid.get();
        long failures = servfail.get();
        long dropped = queueDropped.get();
        long https = httpsBlocked.get();
        long allowedNow = allowed.get();
        long blockedNow = blocked.get();
        return new Snapshot(allowedNow, blockedNow, failures, dropped, unknown, https);
    }

    public static final class Snapshot {
        public final long allowed;
        public final long blocked;
        public final long servfail;
        public final long queueDropped;
        public final long unknownUid;
        public final long httpsBlocked;

        Snapshot(long allowed, long blocked, long servfail, long queueDropped, long unknownUid, long httpsBlocked) {
            this.allowed = allowed;
            this.blocked = blocked;
            this.servfail = servfail;
            this.queueDropped = queueDropped;
            this.unknownUid = unknownUid;
            this.httpsBlocked = httpsBlocked;
        }
    }
}
