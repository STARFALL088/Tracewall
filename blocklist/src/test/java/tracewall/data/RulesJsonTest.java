package tracewall.data;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RulesJsonTest {
    private String valid() {
        Map<String, Boolean> toggles = new HashMap<>();
        for (String category : RulesJson.CATEGORIES) toggles.put(category, !category.equals("ads"));
        return new RulesJson(Arrays.asList("SAFE.org", "*.trusted.org", "safe.org"),
            Arrays.asList(10001, Integer.MAX_VALUE), toggles).serialize();
    }
    @Test void roundTripsNormalizesAndDeduplicates() {
        RulesJson parsed = RulesJson.parse(valid());
        assertEquals(Set.of("safe.org", "*.trusted.org"), parsed.allow);
        assertEquals(Set.of(10001, Integer.MAX_VALUE), parsed.blockedUids);
        assertFalse(parsed.categories.get("ads"));
        assertEquals(parsed.serialize(), RulesJson.parse(parsed.serialize()).serialize());
    }
    @Test void rejectsUnknownFieldsMissingFieldsAndWrongTypes() {
        for (String invalid : new String[] {valid().replace("\"version\":1", "\"version\":2"),
                valid().replace("\"version\":1", "\"version\":\"1\""),
                valid().replace("\"allow\"", "\"unknown\""),
                valid().replace("10001", "-1"), valid().replace("10001", "2147483648"),
                valid().replace("10001", "1.5"), valid().replace("10001", "\"10001\""),
                valid().replace("true", "\"true\""), valid().replace("safe.org", "bad/domain"),
                valid().replace("\"ads\"", "\"unknown\""), valid() + " garbage", "{}", "[]"})
            assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(invalid), invalid);
    }
    @Test void capsFileAndEntryCountsBeforeApplying() {
        assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(" ".repeat(RulesJson.MAX_BYTES + 1)));
        String tooMany = valid().replace("\"allow\":[\"*.trusted.org\",\"safe.org\"]",
            "\"allow\":[" + "\"a.test\",".repeat(1000) + "\"b.test\"]");
        assertNotEquals(valid(), tooMany);
        assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(tooMany));
    }
}
