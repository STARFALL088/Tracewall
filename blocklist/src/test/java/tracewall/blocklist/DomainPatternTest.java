package tracewall.blocklist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DomainPatternTest {

    @Test
    void exactMatchesOnlyItself() {
        DomainPattern p = DomainPattern.parse("example.com");
        assertNotNull(p);
        assertEquals(DomainPattern.Kind.EXACT, p.kind());
        assertTrue(p.matches("example.com"));
        assertTrue(p.matches("EXAMPLE.COM"));
        assertTrue(p.matches("example.com."));          // trailing dot
        assertFalse(p.matches("a.example.com"));
        assertFalse(p.matches("example.com.evil.net"));
    }

    @Test
    void wildcardMatchesAnyDepthBelowButNotTheBareDomain() {
        DomainPattern p = DomainPattern.parse("*.example.com");
        assertNotNull(p);
        assertEquals(DomainPattern.Kind.WILDCARD, p.kind());
        assertTrue(p.matches("a.example.com"));
        assertTrue(p.matches("a.b.c.example.com"));
        assertFalse(p.matches("example.com"));          // the classic gotcha
    }

    @Test
    void wildcardIsLabelAlignedSoLookalikeHostsDoNotMatch() {
        DomainPattern p = DomainPattern.parse("*.example.com");
        assertNotNull(p);
        assertFalse(p.matches("notexample.com"));
        assertFalse(p.matches("example.com.evil.net"));
        assertFalse(p.matches("xexample.com"));
    }

    @Test
    void hyphenatedLookalikeSuffixIsStillRejected() {
        DomainPattern p = DomainPattern.parse("*.example.com");
        assertNotNull(p);
        assertFalse(p.matches("evil-example.com"));
    }

    @Test
    void malformedPatternsAreRejectedNotGuessed() {
        assertNull(DomainPattern.parse(""));
        assertNull(DomainPattern.parse("*"));
        assertNull(DomainPattern.parse("*.*"));
        assertNull(DomainPattern.parse("a.*.b"));
        assertNull(DomainPattern.parse("*.example.com.*"));
        assertNull(DomainPattern.parse(".example.com"));
        assertNull(DomainPattern.parse("example..com"));
        assertNull(DomainPattern.parse("exa mple.com"));
        assertNull(DomainPattern.parse("exämple.com"));   // non-ASCII / IDN
        assertNull(DomainPattern.parse("*."));
    }

    @Test
    void numericAndHyphenLabelsAreLegal() {
        assertNotNull(DomainPattern.parse("a-b.example.com"));
        assertNotNull(DomainPattern.parse("3m.com"));
    }

    @Test
    void overlongLabelIsRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append('a');
        assertNull(DomainPattern.parse(sb + ".com"));
    }

    @Test
    void nullInputIsRejected() {
        assertNull(DomainPattern.parse(null));
    }
}
