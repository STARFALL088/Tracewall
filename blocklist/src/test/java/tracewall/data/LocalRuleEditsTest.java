package tracewall.data;

import java.util.*;
import org.junit.jupiter.api.Test;
import tracewall.blocklist.ScheduledProfile;
import tracewall.blocklist.WeeklySchedule;
import static org.junit.jupiter.api.Assertions.*;

class LocalRuleEditsTest {
    private static RulesJson empty() {
        Map<String, Boolean> toggles = new TreeMap<>();
        for (String name : RulesJson.CATEGORIES) toggles.put(name, true);
        return new RulesJson(Set.of(), Set.of(42), toggles);
    }
    private static ScheduledProfile profile(String name) {
        return new ScheduledProfile(name, new WeeklySchedule(true, 1, 0, 1), Set.of("ads"), Set.of());
    }

    @Test void switchingIdenticalPermanentAndTemporaryAllowsNeverLeavesBoth() {
        RulesJson original = empty();
        RulesJson permanent = LocalRuleEdits.permanentAllow(original, "SAFE.test");
        RulesJson timed = LocalRuleEdits.temporaryAllow(permanent, "safe.test", 1, 1000);
        assertTrue(original.allow.isEmpty());
        assertEquals(Set.of("safe.test"), permanent.allow);
        assertTrue(timed.allow.isEmpty());
        assertEquals(Map.of("safe.test", 61_000L), timed.temporaryAllows);
        assertEquals(permanent.serialize(), LocalRuleEdits.permanentAllow(timed, "safe.test").serialize());
    }

    @Test void independentIntentsPreserveOtherRuleFamiliesAndRemovalIsExact() {
        RulesJson a = LocalRuleEdits.deny(empty(), "*.ads.test", true);
        RulesJson b = LocalRuleEdits.temporaryAllow(a, "safe.test", 5, 1000);
        RulesJson c = LocalRuleEdits.profile(b, null, profile("Study"));
        RulesJson removed = LocalRuleEdits.removeAllow(c, "safe.test", true);
        assertEquals(a.deny, removed.deny);
        assertEquals(a.blockedUids, removed.blockedUids);
        assertEquals(a.categories, removed.categories);
        assertEquals("Study", removed.profiles.get(0).name);
        assertEquals(1, c.temporaryAllows.size());
        assertTrue(removed.temporaryAllows.isEmpty());
        assertEquals(a.serialize(), LocalRuleEdits.deny(a, "other.test", false).serialize());
        assertTrue(LocalRuleEdits.deny(a, "*.ads.test", false).deny.isEmpty());
    }

    @Test void profileRenameEnableDeleteAndStaleEditHaveValidatedAtomicSemantics() {
        RulesJson one = LocalRuleEdits.profile(empty(), null, profile("Study"));
        ScheduledProfile disabled = new ScheduledProfile("Sleep", new WeeklySchedule(false, 64, 1320, 360), Set.of("adult"), Set.of(55));
        RulesJson renamed = LocalRuleEdits.profile(one, "Study", disabled);
        assertEquals("Sleep", renamed.profiles.get(0).name);
        assertFalse(renamed.profiles.get(0).schedule.enabled);
        assertEquals("Study", one.profiles.get(0).name);
        assertThrows(IllegalArgumentException.class, () -> LocalRuleEdits.profile(renamed, "Study", profile("Resurrected")));
        assertThrows(IllegalArgumentException.class, () -> LocalRuleEdits.profile(renamed, null, profile("sleep")));
        assertEquals(empty().serialize(), LocalRuleEdits.profile(renamed, "Sleep", null).serialize());
    }

    @Test void invalidIntentsLeaveThePreviousImmutableDocumentUntouched() {
        RulesJson current = LocalRuleEdits.deny(empty(), "blocked.test", true);
        String saved = current.serialize();
        assertThrows(IllegalArgumentException.class, () -> LocalRuleEdits.temporaryAllow(current, "safe.test", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LocalRuleEdits.deny(current, "https://bad.test", true));
        assertEquals(saved, current.serialize());
    }

    @Test void advancedUidInputIsBoundedAsciiAndRejectsNegativeUnknownOrOverflow() {
        assertEquals(Set.of(0, 42, Integer.MAX_VALUE), LocalRuleEdits.parseUidInput("0, 42\n2147483647 42"));
        assertTrue(LocalRuleEdits.parseUidInput(" \n ").isEmpty());
        for (String bad : new String[] {"-1", "1.0", "1e3", "+1", "2147483648", "১২", "pkg.name", ",42", "42,", "1 ".repeat(1001)})
            assertThrows(IllegalArgumentException.class, () -> LocalRuleEdits.parseUidInput(bad), bad);
    }
}
