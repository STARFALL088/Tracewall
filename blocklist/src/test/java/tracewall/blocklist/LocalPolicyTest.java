package tracewall.blocklist;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalPolicyTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long MONDAY_0900 = Instant.parse("2026-10-26T09:00:00Z").toEpochMilli();
    private static DomainSet set(String... patterns) { return DomainSet.of(Arrays.asList(patterns)); }
    private static ScheduledProfile profile(String name, boolean enabled, Collection<String> cats, Collection<Integer> uids) {
        return new ScheduledProfile(name, new WeeklySchedule(enabled, 1, 540, 600), cats, uids);
    }

    @Test void localPrecedenceIsAllowThenCustomDenyThenAppsThenCategories() {
        PolicyEngine policy = new PolicyEngine(set("safe.test"), Map.of("ads", set("*.blocked.test", "safe.test")),
                Set.of(42), set("*.blocked.test", "safe.test"), Map.of("one.blocked.test", MONDAY_0900 + 1), null, null);
        assertEquals(PolicyEngine.Verdict.ALLOW_LISTED, policy.decide("safe.test", 42, MONDAY_0900, UTC).verdict);
        assertEquals("temporary_allow", policy.decide("one.blocked.test", 42, MONDAY_0900, UTC).reason);
        assertEquals(PolicyEngine.Verdict.BLOCK_CUSTOM, policy.decide("two.blocked.test", 42, MONDAY_0900, UTC).verdict);
        assertEquals("custom_deny", policy.decide("one.blocked.test", 42, MONDAY_0900 + 1, UTC).reason);
        assertEquals(PolicyEngine.Verdict.BLOCK_APP, policy.decide("unlisted.test", 42, MONDAY_0900, UTC).verdict);
        assertFalse(policy.decide("blocked.test", -1, MONDAY_0900, UTC).blocked());
        assertFalse(policy.decide("notblocked.test", -1, MONDAY_0900, UTC).blocked());
        assertTrue(policy.decide("A.B.BLOCKED.TEST.", -1, MONDAY_0900, UTC).blocked());
    }

    @Test void expiryIsAbsoluteAndExclusiveAcrossReconstructedEngines() {
        Map<String, Long> saved = Map.of("*.blocked.test", MONDAY_0900 + 60_000);
        for (int restart = 0; restart < 2; restart++) {
            PolicyEngine policy = new PolicyEngine(null, null, null, set("*.blocked.test"), saved, null, null);
            assertFalse(policy.decide("a.blocked.test", 1, MONDAY_0900 + 59_999, UTC).blocked());
            assertTrue(policy.decide("a.blocked.test", 1, MONDAY_0900 + 60_000, UTC).blocked());
            assertTrue(policy.decide("a.blocked.test", 1, MONDAY_0900 + 60_001, UTC).blocked());
        }
    }

    @Test void timedDurationAndTimestampValidationAvoidOverflow() {
        assertEquals(MONDAY_0900 + 60_000, TimedAllow.expiresAfterMinutes(MONDAY_0900, 1));
        assertEquals(MONDAY_0900 + 604_800_000, TimedAllow.expiresAfterMinutes(MONDAY_0900, 10080));
        for (int duration : new int[] {-1, 0, 10081, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> TimedAllow.expiresAfterMinutes(MONDAY_0900, duration));
        assertThrows(IllegalArgumentException.class, () -> TimedAllow.expiresAfterMinutes(Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> TimedAllow.expiresAfterMinutes(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimedAllow(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimedAllow(DomainPattern.parse("a.test"), 0));
    }

    @Test void overlappingProfilesAreAdditiveDeterministicAndTimeBounded() {
        ScheduledProfile z = profile("Z", true, Set.of("adult"), Set.of(55));
        ScheduledProfile a = profile("A", true, Set.of("ads"), Set.of(55));
        ScheduledProfile disabled = profile("Disabled", false, Set.of("malware"), Set.of(99));
        PolicyEngine policy = new PolicyEngine(null, Map.of("gambling", set("base.test")), Set.of(44),
                null, null, Map.of("adult", set("adult.test"), "ads", set("ads.test"), "malware", set("disabled.test")),
                Arrays.asList(z, disabled, a));
        assertEquals("profile:A", policy.decide("unlisted.test", 55, MONDAY_0900, UTC).reason);
        assertEquals("profile:A:ads", policy.decide("ads.test", -1, MONDAY_0900, UTC).reason);
        assertEquals("profile:Z:adult", policy.decide("adult.test", -1, MONDAY_0900, UTC).reason);
        assertFalse(policy.decide("disabled.test", 99, MONDAY_0900, UTC).blocked());
        assertFalse(policy.decide("adult.test", 55, MONDAY_0900 - 1, UTC).blocked());
        assertFalse(policy.decide("adult.test", 55, MONDAY_0900 + 3_600_000, UTC).blocked());
        assertTrue(policy.decide("base.test", 1, MONDAY_0900 + 3_600_000, UTC).blocked());
        assertTrue(policy.isAppBlocked(44, MONDAY_0900 + 3_600_000, UTC));
        assertTrue(policy.isAppBlocked(55, MONDAY_0900, UTC));
        assertFalse(policy.isAppBlocked(55, MONDAY_0900 + 3_600_000, UTC));
        assertFalse(policy.isAppBlocked(-1, MONDAY_0900, UTC));
    }

    @Test void localExceptionsNeverWeakenManagedBlocksAndManagedAllowStillWins() {
        PolicyEngine managed = new PolicyEngine(set("parent-allow.test"), Map.of("adult", set("parent-block.test")), Set.of(77));
        PolicyEngine policy = new PolicyEngine(set("parent-block.test"), null, null,
                set("parent-allow.test"), Map.of("unlisted.test", MONDAY_0900 + 1), null, null, managed);
        assertEquals(PolicyEngine.Verdict.BLOCK_CATEGORY, policy.decide("parent-block.test", 1, MONDAY_0900, UTC).verdict);
        assertEquals(PolicyEngine.Verdict.BLOCK_APP, policy.decide("unlisted.test", 77, MONDAY_0900, UTC).verdict);
        assertEquals(PolicyEngine.Verdict.ALLOW_LISTED, policy.decide("parent-allow.test", 77, MONDAY_0900, UTC).verdict);
    }

    @Test void constructorDefensivelyCopiesMutableCollections() {
        Set<Integer> uids = new HashSet<>(Set.of(42));
        Map<String, Long> timed = new HashMap<>(Map.of("safe.test", MONDAY_0900 + 1));
        List<ScheduledProfile> profiles = new ArrayList<>(List.of(profile("A", true, Set.of("ads"), Set.of(55))));
        PolicyEngine policy = new PolicyEngine(null, null, uids, null, timed, null, profiles);
        uids.clear(); timed.clear(); profiles.clear();
        assertTrue(policy.decide("any.test", 42, MONDAY_0900, UTC).blocked());
        assertTrue(policy.isAppBlocked(55, MONDAY_0900, UTC));
        assertEquals("temporary_allow", policy.decide("safe.test", 42, MONDAY_0900, UTC).reason);
    }
}
