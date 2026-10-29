package tracewall.data;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import tracewall.blocklist.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesJsonV2Test {
    private static Map<String, Boolean> toggles() {
        return new TreeMap<>(Map.of("ads", false, "adult", true, "malware", true, "gambling", false));
    }
    private static ScheduledProfile profile(String name, Collection<Integer> uids) {
        return new ScheduledProfile(name, new WeeklySchedule(true, 64, 1320, 360), Set.of("ads"), uids);
    }
    private static RulesJson rules() {
        return new RulesJson(Set.of("safe.test"), Set.of(42), toggles(), Set.of("*.denied.test"),
                Map.of("temp.test", 1_000_000L), List.of(profile("পড়া", Set.of(55))));
    }

    @Test void legacyVersionOneImportsWithEmptyNewFamiliesAndExportsAsVersionTwo() {
        String old = "{\"version\":1,\"allow\":[\"SAFE.test\",\"*.trusted.test\"],\"blocked_uids\":[42],"
                + "\"categories\":{\"ads\":false,\"adult\":true,\"gambling\":false,\"malware\":true}}";
        RulesJson legacy = RulesJson.parse(old);
        assertEquals(Set.of("safe.test", "*.trusted.test"), legacy.allow);
        assertEquals(Set.of(42), legacy.blockedUids);
        assertEquals(toggles(), legacy.categories);
        assertTrue(legacy.deny.isEmpty());
        assertTrue(legacy.temporaryAllows.isEmpty());
        assertTrue(legacy.profiles.isEmpty());
        assertEquals(2, new JSONObject(legacy.serialize()).getInt("version"));
        assertEquals(legacy.serialize(), RulesJson.parse(legacy.serialize()).serialize());
    }

    @Test void everyNewFieldRoundTripsWithoutExtendingExpiredAllows() {
        RulesJson before = rules();
        RulesJson after = RulesJson.parse(before.serialize());
        assertEquals(before.serialize(), after.serialize());
        assertEquals(before.deny, after.deny);
        assertEquals(Map.of("temp.test", 1_000_000L), after.temporaryAllows);
        ScheduledProfile p = after.profiles.get(0);
        assertEquals("পড়া", p.name);
        assertEquals(64, p.schedule.days);
        assertEquals(1320, p.schedule.startMinute);
        assertEquals(360, p.schedule.endMinute);
        assertTrue(p.schedule.enabled);
        assertEquals(Set.of(55), p.blockedUids);
        PolicyEngine restored = new PolicyEngine(null, null, Set.of(42), null, after.temporaryAllows, null, null);
        assertFalse(restored.decide("temp.test", 42, 999_999, TimeZone.getTimeZone("UTC")).blocked());
        assertTrue(restored.decide("temp.test", 42, 1_000_000, TimeZone.getTimeZone("UTC")).blocked());
    }

    @Test void newSchemaRejectsMissingFieldsUnknownFieldsWrongTypesAndDuplicatePatterns() {
        JSONObject base = new JSONObject(rules().serialize());
        for (String key : List.of("deny", "temporary_allows", "profiles")) {
            JSONObject missing = new JSONObject(base.toString());
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(missing.toString()));
        }
        for (Object invalid : List.of(0, -1, 1.5, "1000", Long.MAX_VALUE, JSONObject.NULL)) {
            JSONObject bad = new JSONObject(base.toString());
            bad.getJSONArray("temporary_allows").getJSONObject(0).put("expires_at_ms", invalid);
            assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(bad.toString()));
        }
        JSONObject duplicate = new JSONObject(base.toString());
        duplicate.getJSONArray("temporary_allows").put(new JSONObject().put("pattern", "TEMP.test").put("expires_at_ms", 42));
        assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(duplicate.toString()));
        JSONObject conflict = new JSONObject(base.toString());
        conflict.getJSONArray("allow").put("temp.test");
        assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(conflict.toString()));
        JSONObject extra = new JSONObject(base.toString()).put("pin", "not-importable");
        assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(extra.toString()));
    }

    @Test void profileShapeAndRangeValidationRejectsAmbiguity() {
        for (Map.Entry<String, Object> change : Map.<String, Object>of(
                "days", 0, "start_minute", 1440, "end_minute", 1320, "enabled", "true",
                "name", "\n", "categories", new JSONArray().put("unknown"),
                "blocked_uids", new JSONArray().put(-1)).entrySet()) {
            JSONObject bad = new JSONObject(rules().serialize());
            bad.getJSONArray("profiles").getJSONObject(0).put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(bad.toString()), change.getKey());
        }
        assertThrows(IllegalArgumentException.class, () -> new ScheduledProfile("Empty", new WeeklySchedule(true, 1, 0, 1), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> profile("x".repeat(41), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new RulesJson(Set.of(), Set.of(), toggles(), Set.of(), Map.of(),
                List.of(profile("Study", Set.of()), profile("study", Set.of()))));
    }

    @Test void allFamiliesHaveIndependentAndAggregateBoundsAndSerializationChecksBytes() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) many.add("d" + i + ".test");
        assertThrows(IllegalArgumentException.class, () -> new RulesJson(Set.of(), Set.of(), toggles(), many, Map.of(), List.of()));
        List<ScheduledProfile> profiles = new ArrayList<>();
        for (int i = 0; i < 9; i++) profiles.add(profile("P" + i, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new RulesJson(Set.of(), Set.of(), toggles(), Set.of(), Map.of(), profiles));
        Set<Integer> uids = new TreeSet<>();
        for (int i = 0; i < 600; i++) uids.add(i);
        assertThrows(IllegalArgumentException.class, () -> new RulesJson(Set.of(), Set.of(), toggles(), Set.of(), Map.of(),
                List.of(profile("A", uids), profile("B", uids))));
        List<String> longRules = new ArrayList<>();
        for (int i = 0; i < 1000; i++) longRules.add("x".repeat(63) + "." + "y".repeat(63) + "." + "z".repeat(63) + ".d" + i + ".test");
        RulesJson tooLarge = new RulesJson(longRules, Set.of(), toggles(), longRules, Map.of(), List.of());
        assertThrows(IllegalArgumentException.class, tooLarge::serialize);
        assertThrows(IllegalArgumentException.class, () -> RulesJson.parse("প".repeat(RulesJson.MAX_BYTES / 2)));
    }

    @Test void strictGrammarBlocksAndroidPermissivenessDuplicateKeysAndNestingBombs() {
        String good = rules().serialize();
        for (String bad : List.of(good.replace("\"version\":2", "\"version\":2,\"version\":2"),
                good.replace("\"version\":2", "\"version\":2,\"\\u0076ersion\":2"),
                good.replace("\"version\":2", "version:2"), good.replace("\"version\":2", "\"version\":02"),
                good.replace("\"version\":2", "\"version\":+2"), good.replace("\"version\":2", "\"version\":2.0"),
                good.replace("\"version\":2", "\"version\":2e0"), "/*comment*/" + good,
                good.substring(0, good.length() - 1) + ",}", good.replace("safe.test", "safe\\xtest"),
                good.replace("safe.test", "safe\ntest"), good + "{}", "[".repeat(20_000) + "]".repeat(20_000)))
            assertThrows(IllegalArgumentException.class, () -> RulesJson.parse(bad));
        assertEquals(good, RulesJson.parse(" \r\n\t" + good + " ").serialize());
    }

    @Test void immutableSnapshotsDoNotRetainOrExposeMutableEditorCollections() {
        Set<String> deny = new HashSet<>(Set.of("a.test"));
        Map<String, Long> timed = new HashMap<>(Map.of("b.test", 1000L));
        List<ScheduledProfile> profiles = new ArrayList<>(List.of(profile("A", Set.of())));
        RulesJson saved = new RulesJson(Set.of(), Set.of(), toggles(), deny, timed, profiles);
        deny.clear(); timed.clear(); profiles.clear();
        assertEquals(Set.of("a.test"), saved.deny);
        assertEquals(Map.of("b.test", 1000L), saved.temporaryAllows);
        assertEquals(1, saved.profiles.size());
        assertThrows(UnsupportedOperationException.class, () -> saved.deny.clear());
        assertThrows(UnsupportedOperationException.class, () -> saved.temporaryAllows.clear());
        assertThrows(UnsupportedOperationException.class, () -> saved.profiles.clear());
    }
}
