package tracewall.blocklist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DomainSetTest {

    @Test
    void membershipAcrossExactAndWildcardHalves() {
        DomainSet set = DomainSet.of(java.util.Arrays.asList(
                "tracker.com", "*.ads.net", "doubleclick.net"));
        assertTrue(set.matches("tracker.com"));
        assertTrue(set.matches("x.ads.net"));
        assertTrue(set.matches("doubleclick.net"));
        assertFalse(set.matches("ads.net"));
        assertFalse(set.matches("nottracker.com"));
        assertEquals(3, set.size());
        assertEquals(1, set.wildcardCount());
    }

    @Test
    void emptySetMatchesNothing() {
        DomainSet set = DomainSet.of(java.util.Collections.<String>emptyList());
        assertFalse(set.matches("example.com"));
        assertEquals(0, set.size());
    }

    @Test
    void queryIsCaseAndTrailingDotInsensitive() {
        DomainSet set = DomainSet.of(java.util.Collections.singletonList("Evil.COM"));
        assertTrue(set.matches("evil.com."));
        assertTrue(set.matches("EVIL.com"));
    }
}
