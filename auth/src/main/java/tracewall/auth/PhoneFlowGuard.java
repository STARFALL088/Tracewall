package tracewall.auth;

/** Memory-only SMS generation/cooldown and one credential completion at a time. */
final class PhoneFlowGuard {
    static final long TIMEOUT_MS = 60_000L;
    private long generation;
    private long cooldownUntil;
    private long reattachUntil;
    private boolean active;
    private boolean completing;
    private boolean autoClaimed;
    private boolean codeSent;

    long start(long now) {
        if (remainingSeconds(now) > 0 || completing) return 0;
        active = true;
        completing = false;
        autoClaimed = false;
        codeSent = false;
        cooldownUntil = now + TIMEOUT_MS;
        // End early rather than risk reentrant verification becoming a new SMS request.
        reattachUntil = now + TIMEOUT_MS - 2_000L;
        return ++generation;
    }

    boolean owns(long token) { return active && token == generation; }
    boolean active() { return active; }
    long generation() { return generation; }

    void sent(long token, long now) {
        if (owns(token) && !codeSent) {
            codeSent = true;
            cooldownUntil = Math.max(cooldownUntil, now + TIMEOUT_MS);
        }
    }

    boolean canReattach(long token, long now) {
        return owns(token) && !completing && now < reattachUntil;
    }

    void stopListening(long token) {
        if (owns(token)) reattachUntil = 0;
    }

    boolean claimCompletion(long token, boolean automatic) {
        if (!owns(token) || completing || (automatic && autoClaimed)) return false;
        if (automatic) autoClaimed = true;
        completing = true;
        return true;
    }

    void completionFailed(long token) {
        if (owns(token)) completing = false;
    }

    void cancel() {
        active = false;
        completing = false;
        ++generation;
        // Keep the cooldown when this retained flow cancels or changes numbers.
        // A destroyed screen/process loses this guard; Firebase's server limits remain authoritative.
    }

    long remainingSeconds(long now) {
        return Math.max(0L, (cooldownUntil - now + 999L) / 1_000L);
    }
}
