package tracewall.data;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** One bounded latest record per configured category, not an ever-growing attempt log. */
public final class FeedHealth {
    public static final int MAX_METADATA_BYTES = 4096;
    public enum Code { NEVER, RUNNING, OK, HTTP, NETWORK, TIMEOUT, UTF8, PARSE, SIZE, SHRINK, STORAGE, METADATA, CANCELLED }
    private static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("category", "attempt", "success", "failureTime", "status", "failure",
        "http", "failureHttp", "url", "etag", "modified", "feedCount", "activeCount", "rawBytes", "mergedBytes", "lines", "duplicates",
        "added", "removed", "policy", "pendingDigest", "pendingCount", "baselineCount")));
    private final JSONObject data;
    private FeedHealth(JSONObject data) { this.data = data; }
    public static FeedHealth empty(FeedSource source) {
        JSONObject json = new JSONObject().put("category", source.category).put("attempt", 0L).put("success", 0L)
            .put("failureTime", 0L).put("status", Code.NEVER.name()).put("failure", Code.NEVER.name())
            .put("http", 0).put("failureHttp", 0).put("url", source.url).put("etag", "").put("modified", "").put("policy", "")
            .put("pendingDigest", "");
        for (String key : Arrays.asList("feedCount", "activeCount", "rawBytes", "mergedBytes", "lines", "duplicates", "added", "removed", "pendingCount", "baselineCount")) json.put(key, 0);
        return decode(json.toString(), source);
    }
    public static FeedHealth decode(String text, FeedSource source) {
        if (text == null || text.length() > MAX_METADATA_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("Health metadata too large");
        }
        try {
            JSONObject json = new JSONObject(text);
            if (!json.keySet().equals(KEYS) || !source.category.equals(string(json, "category"))) throw new IllegalArgumentException("Health schema/category mismatch");
            for (String key : Arrays.asList("attempt", "success", "failureTime")) number(json, key, 32_503_680_000_000L);
            Code.valueOf(string(json, "status")); Code.valueOf(string(json, "failure"));
            long http = number(json, "http", 599);
            if (http != 0 && http < 100) throw new IllegalArgumentException("Invalid HTTP status");
            long failureHttp = number(json, "failureHttp", 599);
            if (failureHttp != 0 && failureHttp < 100) throw new IllegalArgumentException("Invalid failure HTTP status");
            FeedSource.https(string(json, "url"));
            if (!FeedSource.safe(string(json, "etag"), 256) || !FeedSource.safe(string(json, "modified"), 256)) throw new IllegalArgumentException("Invalid response metadata");
            for (String key : Arrays.asList("feedCount", "activeCount", "rawBytes", "mergedBytes", "lines", "duplicates", "added", "removed", "pendingCount", "baselineCount")) number(json, key, ListData.MAX_BYTES);
            String policy = string(json, "policy");
            if (!policy.isEmpty()) FeedUpdate.Policy.valueOf(policy);
            boolean accepted = json.getLong("success") != 0;
            if (accepted == policy.isEmpty() || (json.getString("status").equals(Code.OK.name()) && !accepted)) {
                throw new IllegalArgumentException("Missing/inconsistent success metadata");
            }
            if (!accepted) {
                for (String key : Arrays.asList("feedCount", "activeCount", "rawBytes", "mergedBytes", "lines", "duplicates", "added", "removed")) {
                    if (json.getInt(key) != 0) throw new IllegalArgumentException("Metrics without accepted feed");
                }
            }
            String digest = string(json, "pendingDigest");
            if (Code.SHRINK.name().equals(json.getString("status"))) {
                if (!digest.matches("[a-f0-9]{64}") || json.getInt("pendingCount") < ListData.MIN_FEED_HOSTS
                        || !FeedUpdate.suspiciousShrink(json.getInt("baselineCount"), json.getInt("pendingCount"))) {
                    throw new IllegalArgumentException("Invalid pending shrink metadata");
                }
            } else if (!digest.isEmpty() || json.getInt("pendingCount") != 0 || json.getInt("baselineCount") != 0) {
                throw new IllegalArgumentException("Unexpected pending shrink metadata");
            }
            if (json.getInt("feedCount") > json.getInt("activeCount") || json.getInt("added") > json.getInt("activeCount")
                    || (!policy.isEmpty() && (json.getInt("feedCount") < ListData.MIN_FEED_HOSTS
                        || json.getInt("rawBytes") == 0 || json.getInt("mergedBytes") == 0))
                    || (policy.equals(FeedUpdate.Policy.FEED_ONLY.name()) && json.getInt("feedCount") != json.getInt("activeCount"))) {
                throw new IllegalArgumentException("Invalid accepted counts");
            }
            return new FeedHealth(json);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid feed health metadata", e); }
    }
    private static String string(JSONObject json, String key) {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string");
        return (String)value;
    }
    private static long number(JSONObject json, String key, long max) {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("Expected integer");
        long n = ((Number)value).longValue();
        if (n < 0 || n > max) throw new IllegalArgumentException("Metadata number out of bounds");
        return n;
    }
    public static final class Response {
        public final int http;
        public final String url, etag, modified;
        public Response(int http, String url, String etag, String modified) {
            if ((http != 0 && http < 100) || http > 599 || !FeedSource.safe(etag, 256) || !FeedSource.safe(modified, 256)) {
                throw new IllegalArgumentException("Invalid response metadata");
            }
            this.http = http; this.url = FeedSource.https(url); this.etag = etag; this.modified = modified;
        }
    }
    private JSONObject copy() { return new JSONObject(data.toString()); }
    private FeedHealth checked(JSONObject json) { return decode(json.toString(), FeedSource.of(category())); }
    private static void clearPending(JSONObject json) { json.put("pendingDigest", "").put("pendingCount", 0).put("baselineCount", 0); }
    private static void response(JSONObject json, Response r) {
        json.put("http", r.http).put("url", r.url).put("etag", r.etag).put("modified", r.modified);
    }
    public FeedHealth attempt(long now) {
        JSONObject json = copy().put("attempt", now).put("status", Code.RUNNING.name());
        clearPending(json);
        response(json, new Response(0, FeedSource.of(category()).url, "", ""));
        return checked(json);
    }
    public FeedHealth accepted(long now, FeedUpdate.Candidate candidate, Response r) {
        if (!candidate.snapshot.category.equals(category())) throw new IllegalArgumentException("Candidate category mismatch");
        JSONObject json = copy().put("success", now).put("status", Code.OK.name())
            .put("feedCount", candidate.snapshot.feedCount).put("activeCount", candidate.activeCount)
            .put("rawBytes", candidate.rawBytes).put("mergedBytes", candidate.mergedBytes).put("lines", candidate.lines)
            .put("duplicates", candidate.duplicates).put("added", candidate.added).put("removed", candidate.removed)
            .put("policy", candidate.snapshot.policy.name());
        clearPending(json); response(json, r);
        return checked(json);
    }
    public FeedHealth rejected(long now, Code code, Response r, FeedUpdate.ShrinkException shrink) {
        if (code == Code.NEVER || code == Code.RUNNING || code == Code.OK || (code == Code.SHRINK) != (shrink != null)) {
            throw new IllegalArgumentException("Invalid failure code");
        }
        JSONObject json = copy().put("failureTime", now).put("status", code.name()).put("failure", code.name()).put("failureHttp", r.http);
        clearPending(json); response(json, r);
        if (shrink != null) json.put("pendingDigest", shrink.digest).put("pendingCount", shrink.candidateCount).put("baselineCount", shrink.previousCount);
        return checked(json);
    }
    public String encode() { return data.toString(); }
    public String category() { return data.getString("category"); }
    public Code status() { return Code.valueOf(data.getString("status")); }
    public Code failure() { return Code.valueOf(data.getString("failure")); }
    public long attempted() { return data.getLong("attempt"); }
    public long succeeded() { return data.getLong("success"); }
    public long failed() { return data.getLong("failureTime"); }
    public int count(String key) {
        if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown metric");
        return data.getInt(key);
    }
    public String metadata(String key) {
        if (!Arrays.asList("url", "etag", "modified", "policy", "pendingDigest").contains(key)) throw new IllegalArgumentException("Unknown metadata");
        return data.getString(key);
    }
}
