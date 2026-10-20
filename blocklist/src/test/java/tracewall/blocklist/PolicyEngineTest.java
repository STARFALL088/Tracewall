package tracewall.blocklist;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {

    private static DomainSet set(String... patterns) {
        return DomainSet.of(Arrays.asList(patterns));
    }

    /** One category. Two-category cases build their map explicitly. */
    private static Map<String, DomainSet> cat(String name, String... patterns) {
        Map<String, DomainSet> m = new LinkedHashMap<>();
        m.put(name, DomainSet.of(Arrays.asList(patterns)));
        return m;
    }


    @Test
    void unknownHostIsAllowedByDefault() {
        PolicyEngine p = new PolicyEngine(null, cat("ads", "doubleclick.net"), null);
        PolicyEngine.Decision d = p.decide("example.com", 10100);
        assertEquals(PolicyEngine.Verdict.ALLOW_DEFAULT, d.verdict);
        assertFalse(d.blocked());
    }

    @Test
    void categoryBlocksAndNamesItselfAsTheReason() {
        PolicyEngine p = new PolicyEngine(null, cat("ads", "doubleclick.net", "*.ads.net"), null);
        // Exact pattern: the bare host matches, a sub-domain of it does not.
        PolicyEngine.Decision d = p.decide("doubleclick.net", 10100);
        assertEquals(PolicyEngine.Verdict.BLOCK_CATEGORY, d.verdict);
        assertEquals("ads", d.reason);
        assertTrue(d.blocked());
    }

    @Test
    void allowlistBeatsACategory() {
        PolicyEngine p = new PolicyEngine(
                set("cdn.doubleclick.net"),
                cat("ads", "*.doubleclick.net"),
                null);
        assertEquals(PolicyEngine.Verdict.ALLOW_LISTED,
                p.decide("cdn.doubleclick.net", 10100).verdict);
        // ... while its siblings stay blocked.
        assertEquals(PolicyEngine.Verdict.BLOCK_CATEGORY,
                p.decide("ads.doubleclick.net", 10100).verdict);
    }

    @Test
    void allowlistBeatsAPerAppBlock() {
        Set<Integer> blockedApps = new HashSet<>(Arrays.asList(10123));
        PolicyEngine p = new PolicyEngine(set("needed.example.org"), null, blockedApps);
        assertEquals(PolicyEngine.Verdict.ALLOW_LISTED,
                p.decide("needed.example.org", 10123).verdict);
    }

    @Test
    void appBlockBeatsACategoryAndSaysSo() {
        Set<Integer> blockedApps = new HashSet<>(Arrays.asList(10123));
        PolicyEngine p = new PolicyEngine(null, cat("ads", "doubleclick.net"), blockedApps);
        PolicyEngine.Decision d = p.decide("doubleclick.net", 10123);
        assertEquals(PolicyEngine.Verdict.BLOCK_APP, d.verdict);
        assertEquals("app", d.reason);
        // A different app is only subject to the category.
        assertEquals(PolicyEngine.Verdict.BLOCK_CATEGORY,
                p.decide("doubleclick.net", 10100).verdict);
    }

    @Test
    void unknownUidDoesNotMatchAPerAppBlock() {
        Set<Integer> blockedApps = new HashSet<>(Arrays.asList(10123));
        PolicyEngine p = new PolicyEngine(null, null, blockedApps);
        assertEquals(PolicyEngine.Verdict.ALLOW_DEFAULT,
                p.decide("example.com", -1).verdict);
    }

    @Test
    void reasonIsStableWhenAHostIsInTwoCategories() {
        Map<String, DomainSet> m = new HashMap<>();
        m.put("zzz", set("shared.example"));
        m.put("ads", set("shared.example"));
        PolicyEngine p = new PolicyEngine(null, m, null);
        // TreeMap ordering: "ads" before "zzz", always.
        assertEquals("ads", p.decide("shared.example", 1).reason);
        assertEquals("ads", p.decide("shared.example", 999).reason);
    }

    @Test
    void wildcardStaysLabelAligned() {
        PolicyEngine p = new PolicyEngine(null, cat("ads", "*.example.com"), null);
        assertTrue(p.decide("a.b.example.com", 1).blocked());
        assertFalse(p.decide("notexample.com", 1).blocked());
        assertFalse(p.decide("example.com", 1).blocked());   // bare domain not covered
    }

    @Test
    void nullHostNeverThrows() {
        PolicyEngine p = new PolicyEngine(null, cat("ads", "x.com"), null);
        assertEquals(PolicyEngine.Verdict.ALLOW_DEFAULT, p.decide(null, 1).verdict);
    }

    @Test
    void emptyEngineAllowsEverything() {
        PolicyEngine p = new PolicyEngine(null, null, null);
        assertEquals(0, p.size());
        assertEquals(0, p.categoryCount());
        assertFalse(p.decide("anything.test", 10000).blocked());
    }

    @Test
    void summaryCountsEveryRuleFamily() {
        PolicyEngine p = new PolicyEngine(
                set("a.com"),
                cat("ads", "b.com", "c.com"),
                new HashSet<>(Arrays.asList(1000)));
        assertEquals(1, p.categoryCount());   // one category, two domains in it
        assertEquals(1, p.allowCount());
        assertEquals(1, p.blockedAppCount());
        assertEquals(4, p.size());
    }
}
