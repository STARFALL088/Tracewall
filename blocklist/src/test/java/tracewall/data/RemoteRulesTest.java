package tracewall.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RemoteRulesTest {
    @Test void absentSnapshotClearsRemoteRulesAndRestoresLocalCategories() {
        RemoteRules previous = RemoteRules.parse(Map.of("allow", Arrays.asList("a.test"),
            "blocked_uids", Arrays.asList(12345L), "categories", Map.of("ads", true)));
        RemoteRules removed = RemoteRules.parse(null);
        assertEquals(Set.of("a.test"), previous.allow);
        assertTrue(removed.allow.isEmpty());
        assertTrue(removed.blockedUids.isEmpty());
        assertNull(removed.categories);
    }

    @Test void acceptsBothFirebaseListRepresentationsAndDeletedIndices() {
        Map<String, Object> allows = new HashMap<>();
        allows.put("0", "EXAMPLE.test.");
        allows.put("2", "*.other.test");
        allows.put("1", null);
        RemoteRules rules = RemoteRules.parse(Map.of("allow", allows,
            "blocked_uids", Arrays.asList(null, 12345L)));
        assertEquals(Set.of("example.test", "*.other.test"), rules.allow);
        assertEquals(Set.of("12345"), rules.blockedUids);
        assertThrows(UnsupportedOperationException.class, () -> rules.allow.clear());
    }

    @Test void removingCategoriesRestoresLocalSelectionInsteadOfKeepingRemoteSelection() {
        assertEquals(Set.of("ads"), RemoteRules.parse(Map.of("categories", Map.of("ads", true))).categories);
        assertNull(RemoteRules.parse(Collections.emptyMap()).categories);
        assertTrue(RemoteRules.parse(Map.of("categories", Map.of("ads", false))).categories.isEmpty());
    }

    @Test void rejectsInvalidShapeDomainsUidsAndToggles() {
        for (Object value : Arrays.asList("rules", Map.of("allow", Arrays.asList("https://example.test")),
                Map.of("blocked_uids", Arrays.asList(-1L)), Map.of("blocked_uids", Arrays.asList(1.5)),
                Map.of("categories", Map.of("unknown", true)), Map.of("categories", Map.of("ads", "yes"))))
            assertThrows(IllegalArgumentException.class, () -> RemoteRules.parse(value));
    }

    @Test void capsRemoteListsBeforeProcessingAndDoesNotMutateSources() {
        assertThrows(IllegalArgumentException.class, () -> RemoteRules.parse(
            Map.of("allow", Collections.nCopies(RulesJson.MAX_ENTRIES + 1, "a.test"))));
        Map<String, Object> input = new HashMap<>();
        input.put("allow", Arrays.asList("a.test"));
        RemoteRules parsed = RemoteRules.parse(input);
        input.clear();
        assertEquals(Set.of("a.test"), parsed.allow);
    }
}
