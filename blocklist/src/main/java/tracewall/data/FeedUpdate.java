package tracewall.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.json.JSONObject;
import tracewall.blocklist.HostListParser;

/** Deterministic replacement/removal policy. Contains no network, Android, disk or clock. */
public final class FeedUpdate {
    public enum Policy { RETAIN_BUNDLED, FEED_ONLY }
    public static final String HEADER = "# tracewall-feed-v1 ";
    private FeedUpdate() {}

    public static final class Snapshot {
        public final String category, digest;
        public final Policy policy;
        public final int feedCount;
        public Snapshot(String category, String digest, Policy policy, int feedCount) {
            FeedSource.of(category);
            if (policy == null || feedCount < ListData.MIN_FEED_HOSTS || feedCount > ListData.MAX_BYTES / 4
                    || digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid cache metadata");
            this.category = category; this.digest = digest; this.policy = policy; this.feedCount = feedCount;
        }
        public String encode() {
            return new JSONObject().put("category", category).put("digest", digest).put("policy", policy.name())
                .put("feedCount", feedCount).toString();
        }
    }
    /** Legacy caches have no feed-count baseline. Never mistake bundled/merged counts for raw feed counts. */
    public static Snapshot snapshot(String cache, FeedSource source) {
        Set<String> active = ListData.validate(cache, ListData.MIN_FEED_HOSTS);
        if (!cache.startsWith(HEADER)) {
            if (cache.startsWith("# tracewall-feed-")) throw new IllegalArgumentException("Unsupported cache metadata version");
            return null;
        }
        int end = cache.indexOf('\n');
        if (end < 0 || end > 1024) throw new IllegalArgumentException("Invalid cache metadata header");
        try {
            JSONObject json = new JSONObject(cache.substring(HEADER.length(), end));
            if (json.length() != 4 || !(json.get("feedCount") instanceof Integer) || !(json.get("category") instanceof String)
                    || !(json.get("digest") instanceof String) || !(json.get("policy") instanceof String)) throw new IllegalArgumentException("Invalid cache metadata fields");
            Snapshot result = new Snapshot(json.getString("category"), json.getString("digest"),
                Policy.valueOf(json.getString("policy")), json.getInt("feedCount"));
            if (!result.category.equals(source.category) || result.feedCount > active.size()
                    || (result.policy == Policy.FEED_ONLY && result.feedCount != active.size())) {
                throw new IllegalArgumentException("Cache metadata/count mismatch");
            }
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid cache metadata", e); }
    }
    public static final class Candidate {
        public final String text;
        public final Snapshot snapshot;
        public final int rawBytes, mergedBytes, lines, duplicates, activeCount, added, removed;
        Candidate(String text, Snapshot snapshot, int rawBytes, HostListParser.Result parsed, int activeCount, int added, int removed) {
            this.text = text; this.snapshot = snapshot; this.rawBytes = rawBytes;
            this.mergedBytes = text.getBytes(StandardCharsets.UTF_8).length;
            this.lines = parsed.linesSeen(); this.duplicates = parsed.duplicates();
            this.activeCount = activeCount; this.added = added; this.removed = removed;
        }
    }
    public static final class ShrinkException extends IllegalArgumentException {
        public final int previousCount, candidateCount;
        public final String digest;
        public ShrinkException(int previous, int candidate, String digest) {
            super("Feed lost more than 25% of its last accepted unique hosts; PIN approval of this exact content is required");
            previousCount = previous; candidateCount = candidate; this.digest = digest;
        }
    }
    public static boolean suspiciousShrink(int previous, int candidate) {
        if (previous < 0 || candidate < 0) throw new IllegalArgumentException("Negative count");
        return (long)candidate * 4 < (long)previous * 3;
    }
    /** Only FRESH (+ optional BUNDLED); PREVIOUS is used for validation, shrink comparison and deltas, never unioned. */
    public static Candidate prepare(FeedSource source, String bundled, String raw, String previous,
                                    Policy policy, String approvedDigest) {
        if (source == null || policy == null) throw new IllegalArgumentException("Missing source/policy");
        HostListParser.Result parsed = ListData.inspect(raw, ListData.MIN_FEED_HOSTS);
        Set<String> before = ListData.validate(previous, ListData.MIN_FEED_HOSTS);
        Snapshot last = snapshot(previous, source);
        String digest = digest(raw);
        if (last != null && suspiciousShrink(last.feedCount, parsed.entriesParsed()) && !digest.equals(approvedDigest)) {
            throw new ShrinkException(last.feedCount, parsed.entriesParsed(), digest);
        }
        Set<String> hosts = new TreeSet<>(parsed.set().patterns());
        if (policy == Policy.RETAIN_BUNDLED) hosts.addAll(ListData.validate(bundled, 1));
        Snapshot next = new Snapshot(source.category, digest, policy, parsed.entriesParsed());
        String body = ListData.assemble(hosts, source.url + "; " + source.attribution);
        String text = HEADER + next.encode() + "\n" + body;
        ListData.validate(text, ListData.MIN_FEED_HOSTS); // header also counts toward the assembled byte cap
        Set<String> added = new HashSet<>(hosts), removed = new HashSet<>(before);
        added.removeAll(before); removed.removeAll(hosts);
        return new Candidate(text, next, ListData.bytes(raw).length, parsed, hosts.size(), added.size(), removed.size());
    }
    public static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(ListData.bytes(text));
            StringBuilder result = new StringBuilder(64);
            for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
