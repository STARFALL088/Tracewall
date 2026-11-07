package tracewall.privacy;

/** Bounded deletion decisions. Acknowledged batches alone can never mean completion. */
public final class CloudDeletion {
    public static final int PAGE_SIZE = 200, MAX_BATCHES = 50, COLLECTION_COUNT = 3;
    public enum Page { DELETE, NEXT_COLLECTION, PENDING }
    private int batches, verified;
    private boolean failed;
    public Page page(int rows, boolean fromCache, boolean pendingWrites) {
        if (failed || rows < 0 || rows > PAGE_SIZE || fromCache || pendingWrites) return fail();
        if (rows == 0) return Page.NEXT_COLLECTION;
        if (batches >= MAX_BATCHES) return fail();
        batches++;
        return Page.DELETE;
    }
    /** Invoke once for each distinct collection, in order, after every batch has been acknowledged. */
    public boolean verify(int collection, int rows, boolean fromCache, boolean pendingWrites) {
        if (failed || collection != verified || verified >= COLLECTION_COUNT || rows != 0 || fromCache || pendingWrites) {
            failed = true;
            return false;
        }
        verified++;
        return true;
    }
    public boolean confirmed() { return !failed && verified == COLLECTION_COUNT; }
    private Page fail() { failed = true; return Page.PENDING; }
}
