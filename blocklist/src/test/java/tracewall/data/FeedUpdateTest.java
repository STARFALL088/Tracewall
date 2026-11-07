package tracewall.data;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedUpdateTest {
    private static final FeedSource SOURCE = FeedSource.of("ads");
    static String hosts(String prefix, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(prefix).append(i).append(".test\n");
        return out.toString();
    }
    private static FeedUpdate.Candidate prepare(String bundled, String raw, String previous, FeedUpdate.Policy policy) {
        return FeedUpdate.prepare(SOURCE, bundled, raw, previous, policy, "");
    }
    @Test void defaultRetainsOnlyBundledPinsButRemovesFormerDownloadOnlyHosts() {
        String bundled = hosts("seed", 100), old = hosts("old", 100), next = hosts("new", 100);
        var first = prepare(bundled, old, bundled, FeedUpdate.Policy.RETAIN_BUNDLED);
        var second = prepare(bundled, next, first.text, FeedUpdate.Policy.RETAIN_BUNDLED);
        Set<String> active = ListData.validate(second.text, 100);
        assertTrue(active.contains("seed0.test"));
        assertFalse(active.contains("old0.test"));
        assertTrue(active.contains("new0.test"));
        assertEquals(200, active.size());
        assertEquals(100, second.added); assertEquals(100, second.removed);
        assertEquals(100, FeedUpdate.snapshot(second.text, SOURCE).feedCount);
    }
    @Test void explicitFeedOnlyCanRemoveBundledSeedsAndRetainOverlapInFreshFeed() {
        String bundled = hosts("seed", 100);
        var previous = prepare(bundled, hosts("old", 100), bundled, FeedUpdate.Policy.RETAIN_BUNDLED);
        var replacement = prepare(bundled, hosts("new", 100) + "seed0.test\n", previous.text, FeedUpdate.Policy.FEED_ONLY);
        Set<String> active = ListData.validate(replacement.text, 100);
        assertEquals(101, active.size()); assertTrue(active.contains("seed0.test"));
        assertFalse(active.contains("seed1.test")); assertFalse(active.contains("old0.test"));
        assertEquals(199, replacement.removed); assertEquals(100, replacement.added);
        assertEquals(FeedUpdate.Policy.FEED_ONLY, replacement.snapshot.policy);
    }
    @Test void changingBackToRetentionRestoresSeedsOnlyOnSuccessfulReplacement() {
        String bundled = hosts("seed", 100);
        var feedOnly = prepare(bundled, hosts("live", 100), bundled, FeedUpdate.Policy.FEED_ONLY);
        var retained = prepare(bundled, hosts("live", 100), feedOnly.text, FeedUpdate.Policy.RETAIN_BUNDLED);
        assertEquals(100, retained.added); assertEquals(0, retained.removed);
    }
    @Test void shrinkUsesUniqueFreshCountNotLargerBundledOrMergedCount() {
        String bundled = hosts("seed", 1000);
        var first = prepare(bundled, hosts("live", 200), bundled, FeedUpdate.Policy.RETAIN_BUNDLED);
        assertDoesNotThrow(() -> prepare(bundled, hosts("live", 150), first.text, FeedUpdate.Policy.FEED_ONLY));
        var error = assertThrows(FeedUpdate.ShrinkException.class,
            () -> prepare(bundled, hosts("live", 149), first.text, FeedUpdate.Policy.RETAIN_BUNDLED));
        assertEquals(200, error.previousCount); assertEquals(149, error.candidateCount);
        assertEquals(FeedUpdate.digest(hosts("live", 149)), error.digest);
    }
    @Test void exactContentApprovalAllowsLegitimateShrinkAndMovesTheBaselineForward() {
        String bundled = hosts("seed", 100), raw = hosts("live", 100);
        var previous = prepare(bundled, hosts("live", 200), bundled, FeedUpdate.Policy.FEED_ONLY);
        assertThrows(FeedUpdate.ShrinkException.class, () -> FeedUpdate.prepare(SOURCE, bundled, raw, previous.text, FeedUpdate.Policy.FEED_ONLY, "0".repeat(64)));
        var approved = FeedUpdate.prepare(SOURCE, bundled, raw, previous.text, FeedUpdate.Policy.FEED_ONLY, FeedUpdate.digest(raw));
        assertEquals(100, approved.removed); assertEquals(100, approved.snapshot.feedCount);
        assertDoesNotThrow(() -> prepare(bundled, raw, approved.text, FeedUpdate.Policy.FEED_ONLY));
        assertThrows(FeedUpdate.ShrinkException.class, () -> FeedUpdate.prepare(SOURCE, bundled, raw + "# changed\n", previous.text, FeedUpdate.Policy.FEED_ONLY, FeedUpdate.digest(raw)));
    }
    @Test void approvalNeverBypassesMinimumMalformedUtf8OrSizeChecks() {
        String baseline = hosts("live", 200), shortList = hosts("live", 99), malformed = hosts("live", 100) + "<html>failure</html>\n";
        var previous = prepare(baseline, baseline, baseline, FeedUpdate.Policy.FEED_ONLY);
        for (String raw : new String[] {shortList, malformed, hosts("live", 100) + "ok.test unexpected\n", hosts("live", 100) + "[unexpected header]\n"}) {
            assertThrows(IllegalArgumentException.class, () -> FeedUpdate.prepare(SOURCE, baseline, raw, previous.text, FeedUpdate.Policy.FEED_ONLY, FeedUpdate.digest(raw)));
        }
        assertThrows(IOException.class, () -> ListData.utf8(new byte[] {(byte)0xc3, 0x28}));
        assertThrows(ListData.SizeException.class, () -> ListData.read(new ByteArrayInputStream(new byte[11]), 10));
        assertThrows(ListData.TooLargeException.class, () -> ListData.validate("#".repeat(ListData.MAX_BYTES + 1), 100));
        assertThrows(ListData.TooLargeException.class, () -> ListData.validate("#" + "বাংলা".repeat(400_000), 100));
    }
    @Test void duplicateRowsCannotHideShrinkAndMetricsAreValidatedUniqueCounts() {
        String raw = hosts("live", 100) + "live0.test\n".repeat(150);
        var old = prepare(raw, hosts("live", 200), raw, FeedUpdate.Policy.FEED_ONLY);
        assertThrows(FeedUpdate.ShrinkException.class, () -> prepare(raw, raw, old.text, FeedUpdate.Policy.FEED_ONLY));
        var candidate = FeedUpdate.prepare(SOURCE, raw, raw, old.text, FeedUpdate.Policy.FEED_ONLY, FeedUpdate.digest(raw));
        assertEquals(100, candidate.snapshot.feedCount); assertEquals(150, candidate.duplicates);
        assertEquals(raw.getBytes(StandardCharsets.UTF_8).length, candidate.rawBytes);
        assertEquals(candidate.text.getBytes(StandardCharsets.UTF_8).length, candidate.mergedBytes);
    }
    @Test void bareFormatThatFitsRawCapCanStillExceedNormalizedMergedCap() {
        StringBuilder raw = new StringBuilder();
        String label = "a".repeat(60) + ".";
        for (int i = 0; i < 20_000; i++) {
            String last = "h" + i;
            raw.append(label.repeat(3)).append(last).append("x".repeat(62 - last.length())).append('\n');
        }
        String text = raw.toString();
        assertTrue(ListData.bytes(text).length < ListData.MAX_BYTES);
        assertEquals(20_000, ListData.validate(text, 100).size());
        assertThrows(ListData.TooLargeException.class, () -> prepare(hosts("seed", 100), text, hosts("old", 100), FeedUpdate.Policy.FEED_ONLY));
    }
    @Test void invalidCacheMetadataCannotSilentlyDisableShrinkProtection() {
        String hosts = hosts("live", 200);
        var valid = prepare(hosts, hosts, hosts, FeedUpdate.Policy.FEED_ONLY);
        String header = valid.text.substring(FeedUpdate.HEADER.length(), valid.text.indexOf('\n'));
        for (JSONObject changed : new JSONObject[] {
            new JSONObject(header).put("feedCount", "200"), new JSONObject(header).put("feedCount", 201),
            new JSONObject(header).put("feedCount", 99), new JSONObject(header).put("digest", "invalid"),
            new JSONObject(header).put("category", "adult"), new JSONObject(header).put("policy", "UNKNOWN"),
            new JSONObject(header).put("extra", true)}) {
            String cache = FeedUpdate.HEADER + changed + "\n" + hosts;
            assertThrows(IllegalArgumentException.class, () -> FeedUpdate.snapshot(cache, SOURCE));
        }
        assertThrows(IllegalArgumentException.class, () -> FeedUpdate.snapshot(valid.text.replace("tracewall-feed-v1", "tracewall-feed-v2"), SOURCE));
        assertNull(FeedUpdate.snapshot(hosts, SOURCE)); // documented legacy minimum-only migration
    }
    @Test void rejectsAttributionInjectionAndNonBlockingAddressRows() {
        assertThrows(IllegalArgumentException.class, () -> ListData.merge(hosts("seed", 100), hosts("live", 100), "source\ninjected.test"));
        assertThrows(IllegalArgumentException.class, () -> ListData.validate(hosts("live", 100) + "8.8.8.8 hijack.test\n", 100));
    }
    @Test void shrinkMathHasAnExplicitBoundaryAndNoIntegerOverflow() {
        assertFalse(FeedUpdate.suspiciousShrink(200, 150)); assertTrue(FeedUpdate.suspiciousShrink(200, 149));
        assertFalse(FeedUpdate.suspiciousShrink(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(FeedUpdate.suspiciousShrink(Integer.MAX_VALUE, 100));
        assertThrows(IllegalArgumentException.class, () -> FeedUpdate.suspiciousShrink(-1, 1));
    }
}
