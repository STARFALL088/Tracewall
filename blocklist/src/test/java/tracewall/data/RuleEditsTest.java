package tracewall.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEditsTest {
    @Test void concurrentAddsRebaseOntoTheLatestList() {
        Map<String, Object> first = RuleEdits.allow(Arrays.asList("a.test"), "b.test", true);
        Map<String, Object> retried = RuleEdits.allow(first, "c.test", true);
        assertEquals(Arrays.asList("a.test", "b.test", "c.test"), new java.util.ArrayList<>(retried.values()));
        assertEquals(2, RuleEdits.allow(retried, "b.test", false).size());
    }
    @Test void duplicateAddsAndMissingRemovalsAreIdempotent() {
        Map<String, Object> one = RuleEdits.allow(null, "EXAMPLE.test.", true);
        assertEquals(one, RuleEdits.allow(one, "example.test", true));
        assertEquals(one, RuleEdits.allow(one, "other.test", false));
        assertTrue(RuleEdits.allow(one, "example.test", false).isEmpty());
    }
    @Test void uidTransactionsPreserveOtherOwnersAndRejectInvalidNumbers() {
        Map<String, Object> a = RuleEdits.blocked(Arrays.asList(12345L), 23456, true);
        assertEquals(Arrays.asList(12345L, 23456L), new java.util.ArrayList<>(a.values()));
        assertEquals(Map.of("0", 23456L), RuleEdits.blocked(a, 12345, false));
        assertThrows(IllegalArgumentException.class, () -> RuleEdits.blocked(a, -1, true));
    }
    @Test void firstCategoryChangePreservesDefaultsAndLaterIndependentChanges() {
        Map<String, Object> first = RuleEdits.category(null, "ads", false);
        assertEquals(Boolean.TRUE, first.get("malware"));
        Map<String, Object> second = RuleEdits.category(first, "adult", false);
        assertEquals(Boolean.FALSE, second.get("ads"));
        assertEquals(Boolean.FALSE, second.get("adult"));
        assertEquals(Boolean.TRUE, second.get("gambling"));
    }
    @Test void sparseExistingCategoriesKeepTheirActualSemantics() {
        Map<String, Object> changed = RuleEdits.category(Map.of("ads", true), "adult", true);
        assertEquals(Boolean.FALSE, changed.get("malware"));
        assertEquals(Boolean.TRUE, changed.get("ads"));
        assertThrows(IllegalArgumentException.class, () -> RuleEdits.category(null, "unknown", true));
    }
    @Test void invalidDomainsAreRejectedInsteadOfSilentlySaved() {
        for (String raw : Arrays.asList("https://example.test", "a..test", "*.test/path", "localhost", ""))
            assertThrows(IllegalArgumentException.class, () -> RuleEdits.domain(raw));
        assertEquals("*.example.test", RuleEdits.domain("*.EXAMPLE.test."));
    }
}
