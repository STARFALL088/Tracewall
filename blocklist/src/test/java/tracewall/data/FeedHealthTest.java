package tracewall.data;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedHealthTest {
    private static final FeedSource SOURCE = FeedSource.of("ads");
    private static FeedHealth.Response response(int http) { return new FeedHealth.Response(http, SOURCE.url, "\"revision\"", "Thu, 29 Oct 2026 12:00:00 GMT"); }
    private static FeedUpdate.Candidate candidate() {
        String bundled = FeedUpdateTest.hosts("seed", 100);
        return FeedUpdate.prepare(SOURCE, bundled, FeedUpdateTest.hosts("live", 100), bundled, FeedUpdate.Policy.RETAIN_BUNDLED, "");
    }
    @Test void lastSuccessMetricsSurviveFailuresAndLastFailureSurvivesRecovery() {
        FeedHealth first = FeedHealth.empty(SOURCE).attempt(1).accepted(2, candidate(), response(200));
        FeedHealth failed = first.attempt(3).rejected(4, FeedHealth.Code.HTTP, response(410), null);
        assertEquals(3, failed.attempted()); assertEquals(2, failed.succeeded()); assertEquals(4, failed.failed());
        assertEquals(200, failed.count("activeCount")); assertEquals(100, failed.count("feedCount"));
        assertEquals(100, failed.count("added")); assertEquals(410, failed.count("failureHttp"));
        FeedHealth recovered = failed.attempt(5).accepted(6, candidate(), response(200));
        assertEquals(FeedHealth.Code.OK, recovered.status()); assertEquals(FeedHealth.Code.HTTP, recovered.failure());
        assertEquals(410, recovered.count("failureHttp")); assertEquals(4, recovered.failed());
        assertEquals(200, recovered.count("http"));
    }
    @Test void healthRoundTripsAndRemainsBoundedRegardlessOfAttemptCount() {
        FeedHealth health = FeedHealth.empty(SOURCE);
        for (int i = 1; i < 500; i++) health = health.attempt(i).rejected(i, FeedHealth.Code.NETWORK, response(0), null);
        String json = health.encode();
        assertTrue(json.length() < FeedHealth.MAX_METADATA_BYTES);
        FeedHealth decoded = FeedHealth.decode(json, SOURCE);
        assertEquals(499, decoded.attempted()); assertEquals(499, decoded.failed());
        assertEquals(SOURCE.url, decoded.metadata("url")); assertEquals("\"revision\"", decoded.metadata("etag"));
    }
    @Test void shrinkMetadataHasExactDigestAndCountsAndClearsAfterNextAttempt() {
        FeedHealth health = FeedHealth.empty(SOURCE).attempt(10).rejected(11, FeedHealth.Code.SHRINK, response(200),
            new FeedUpdate.ShrinkException(200, 100, "a".repeat(64)));
        assertEquals("a".repeat(64), health.metadata("pendingDigest"));
        assertEquals(100, health.count("pendingCount")); assertEquals(200, health.count("baselineCount"));
        assertEquals("", health.attempt(12).metadata("pendingDigest"));
        assertThrows(IllegalArgumentException.class, () -> health.rejected(12, FeedHealth.Code.SHRINK, response(200), null));
        assertThrows(IllegalArgumentException.class, () -> health.rejected(12, FeedHealth.Code.OK, response(200), null));
    }
    @Test void rejectsMissingUnknownWrongTypedAndOutOfRangeMetadata() {
        String original = FeedHealth.empty(SOURCE).encode();
        JSONObject missing = new JSONObject(original); missing.remove("attempt");
        assertThrows(IllegalArgumentException.class, () -> FeedHealth.decode(missing.toString(), SOURCE));
        for (JSONObject invalid : new JSONObject[] {
            new JSONObject(original).put("extra", true), new JSONObject(original).put("category", "adult"),
            new JSONObject(original).put("attempt", "123"), new JSONObject(original).put("attempt", -1),
            new JSONObject(original).put("attempt", 1.5), new JSONObject(original).put("attempt", Long.MAX_VALUE),
            new JSONObject(original).put("rawBytes", ListData.MAX_BYTES + 1), new JSONObject(original).put("http", 99),
            new JSONObject(original).put("http", 600), new JSONObject(original).put("status", "CUSTOM"),
            new JSONObject(original).put("policy", "UNKNOWN"), new JSONObject(original).put("feedCount", 1),
            new JSONObject(original).put("pendingDigest", "a".repeat(64)),
            new JSONObject(original).put("url", "http://example.test/list"), new JSONObject(original).put("etag", "x\r\ninjected"),
            new JSONObject(original).put("modified", "x".repeat(257))}) {
            assertThrows(IllegalArgumentException.class, () -> FeedHealth.decode(invalid.toString(), SOURCE), invalid.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> FeedHealth.decode("x".repeat(FeedHealth.MAX_METADATA_BYTES + 1), SOURCE));
    }
    @Test void rejectsInconsistentShrinksAndAcceptedCounts() {
        JSONObject good = new JSONObject(FeedHealth.empty(SOURCE).attempt(1).accepted(2, candidate(), response(200)).encode());
        for (JSONObject invalid : new JSONObject[] {
            new JSONObject(good.toString()).put("policy", "FEED_ONLY"),
            new JSONObject(good.toString()).put("mergedBytes", 0), new JSONObject(good.toString()).put("added", 201),
            new JSONObject(good.toString()).put("status", "SHRINK").put("pendingCount", 199).put("baselineCount", 200).put("pendingDigest", "a".repeat(64)),
            new JSONObject(good.toString()).put("status", "SHRINK").put("pendingCount", 99).put("baselineCount", 200).put("pendingDigest", "a".repeat(64))}) {
            assertThrows(IllegalArgumentException.class, () -> FeedHealth.decode(invalid.toString(), SOURCE));
        }
    }
    @Test void responseAndSourceMetadataRejectInjectionAndCredentialsWithoutChangingConfiguredSources() {
        assertEquals(4, FeedSource.ALL.size());
        assertThrows(IllegalArgumentException.class, () -> FeedSource.of("other"));
        for (String url : new String[] {"http://example.test/list", "https://user:password@example.test/list", "https://example.test/list\n", "https://example.test/list#fragment", "https:///missing-host"}) {
            assertThrows(IllegalArgumentException.class, () -> new FeedHealth.Response(200, url, "", ""));
        }
        assertThrows(IllegalArgumentException.class, () -> new FeedHealth.Response(200, SOURCE.url, "\u0000", ""));
        assertEquals("", FeedSource.header("unsafe\r\nheader")); assertEquals("", FeedSource.header("x".repeat(257)));
        assertEquals("safe", FeedSource.header("safe"));
    }
    @Test void neverBorrowsSuccessMetricsFromADifferentCategory() {
        FeedSource adult = FeedSource.of("adult");
        assertThrows(IllegalArgumentException.class, () -> FeedHealth.decode(FeedHealth.empty(SOURCE).encode(), adult));
        assertThrows(IllegalArgumentException.class, () -> FeedHealth.empty(adult).accepted(5, candidate(), response(200)));
    }
}
