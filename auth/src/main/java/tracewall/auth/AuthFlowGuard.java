package tracewall.auth;

/** Main-thread operation ownership; stale/duplicate callbacks cannot finish newer work. */
final class AuthFlowGuard {
    private long generation;
    private long owner;

    long begin() {
        if (owner != 0) return 0;
        owner = ++generation;
        return owner;
    }

    boolean owns(long token) { return token != 0 && owner == token; }
    boolean busy() { return owner != 0; }
    boolean finish(long token) {
        if (!owns(token)) return false;
        owner = 0;
        return true;
    }
}
