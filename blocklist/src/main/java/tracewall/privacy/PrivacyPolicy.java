package tracewall.privacy;

/** Immutable consent snapshot. Any change invalidates previously captured async work (including ABA). */
public final class PrivacyPolicy {
    public static final long DENIED = -1;
    public final boolean cloud, history, inventory, deletionPending;
    public final long generation;

    public PrivacyPolicy(boolean cloud, boolean history, boolean inventory, boolean deletionPending) {
        this(cloud && !deletionPending, history, inventory, deletionPending, 0);
    }
    private PrivacyPolicy(boolean cloud, boolean history, boolean inventory, boolean pending, long generation) {
        this.cloud = cloud; this.history = history; this.inventory = inventory;
        this.deletionPending = pending; this.generation = generation;
    }
    public static PrivacyPolicy defaults() { return new PrivacyPolicy(false, false, false, false); }
    public boolean allowsCloud() { return cloud && !deletionPending; }
    public long historyTicket() { return allowsCloud() && history ? generation : DENIED; }
    public boolean acceptsCloud(long ticket) { return ticket >= 0 && ticket == generation && allowsCloud(); }
    public boolean acceptsHistory(long ticket) { return history && acceptsCloud(ticket); }
    public boolean acceptsInventory(long ticket, boolean sharing) {
        return inventory == sharing && acceptsCloud(ticket);
    }
    public PrivacyPolicy withCloud(boolean enabled) {
        if (deletionPending && enabled) throw new IllegalStateException("Deletion must be verified first");
        return next(enabled, history, inventory, deletionPending);
    }
    public PrivacyPolicy withHistory(boolean enabled) {
        if (deletionPending && enabled) throw new IllegalStateException("Deletion must be verified first");
        return next(cloud, enabled, inventory, deletionPending);
    }
    public PrivacyPolicy withInventory(boolean enabled) {
        if (deletionPending && enabled) throw new IllegalStateException("Deletion must be verified first");
        return next(cloud, history, enabled, deletionPending);
    }
    /** Approved deletion stops all publishers and leaves every sharing consent off, even after success. */
    public PrivacyPolicy beginDeletion() {
        return new PrivacyPolicy(false, false, false, true, generation + 1);
    }
    public PrivacyPolicy finishDeletion() {
        if (!deletionPending) throw new IllegalStateException("No deletion pending");
        return new PrivacyPolicy(false, false, false, false, generation + 1);
    }
    private PrivacyPolicy next(boolean c, boolean h, boolean i, boolean p) {
        if (c == cloud && h == history && i == inventory && p == deletionPending) return this;
        return new PrivacyPolicy(c, h, i, p, generation + 1);
    }
}
