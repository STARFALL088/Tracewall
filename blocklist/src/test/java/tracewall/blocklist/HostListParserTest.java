package tracewall.blocklist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HostListParserTest {

    /** 13 entries: 5 comment/header lines, then 8 host lines, last one a dup. */
    private static final String SAMPLE = String.join("\n",
            "# This is a comment",
            "! another comment style",
            "[Adblock Plus 2.0]",
            "0.0.0.0 ads.example.com",
            "127.0.0.1  tracker.net",
            "::1         ipv6.example.com",
            "0:0:0:0:0:0:0:1  longipv6.example.com",
            "bare.example.com",
            "0.0.0.0 *.wildads.com",
            "0.0.0.0 ads.example.com",
            "address=/adblock-syntax.example/",
            "garbage with spaces and no host !!!",
            "");

    private final HostListParser.Result r = HostListParser.parse(SAMPLE);

    @Test
    void parsesEveryAcceptedShape() {
        assertTrue(r.set().matches("ads.example.com"));
        assertTrue(r.set().matches("tracker.net"));
        assertTrue(r.set().matches("ipv6.example.com"));
        assertTrue(r.set().matches("longipv6.example.com"));
        assertTrue(r.set().matches("bare.example.com"));
    }

    @Test
    void wildcardLineKeepsWildcardSemantics() {
        assertTrue(r.set().matches("cdn.wildads.com"));
        assertFalse(r.set().matches("wildads.com"));
    }

    @Test
    void duplicateLineIsDedupedAndCounted() {
        assertEquals(1, r.duplicates());
    }

    @Test
    void adblockSyntaxJunkAndDotlessHostsAreRejected() {
        // address=/... , the "garbage with..." line, and a dotless token
        assertTrue(r.malformed() >= 2);
        assertFalse(r.set().matches("adblock-syntax.example"));
        assertFalse(r.set().matches("garbage"));
    }

    @Test
    void commentsAndHeadersAreSkippedNotCountedAsMalformed() {
        // The 3 comment/header lines must not land in the malformed bucket.
        // Only genuine junk does.
        assertEquals(2, r.malformed(), "only address=/ and the garbage line");
    }

    @Test
    void countsAreInternallyConsistent() {
        // 3 comment/header lines + 1 trailing empty line are not content.
        int contentLines = r.linesSeen() - 3 - 1;
        assertEquals(contentLines, r.entriesParsed() + r.malformed() + r.duplicates());
        // entriesParsed already excludes duplicates, so set size equals it.
        assertEquals(r.entriesParsed(), r.set().size());
        assertEquals(6, r.entriesParsed());
        assertEquals(2, r.malformed());
        assertEquals(1, r.duplicates());
    }

    @Test
    void emptyInputYieldsEmptySetNotACrash() {
        HostListParser.Result e = HostListParser.parse("");
        assertEquals(0, e.entriesParsed());
        assertEquals(0, e.malformed());
        assertEquals(0, e.set().size());
        assertFalse(e.set().matches("example.com"));
    }

    @Test
    void mergesAliasesInlineCommentsAndDuplicatesAcrossPrefixes() {
        HostListParser.Result result = HostListParser.parse(
            "# header\r\n0.0.0.0 ADS.example.com tracker.example.com # note\n"
            + "127.0.0.1 ads.example.com\ntracker.example.com#inline\n"
            + "127.0.0.1 localhost localhost.localdomain\n::1 ip6-localhost ip6-loopback");
        assertEquals(2, result.entriesParsed());
        assertEquals(2, result.duplicates());
        assertEquals(0, result.malformed());
        assertTrue(result.set().matches("tracker.example.com"));
        assertFalse(result.set().matches("localhost.localdomain"));
    }

    @Test
    void nullInputYieldsEmptySetNotACrash() {
        HostListParser.Result n = HostListParser.parse(null);
        assertEquals(0, n.entriesParsed());
        assertEquals(0, n.malformed());
    }

    @Test
    void leadingAndTrailingWhitespaceAndCRLFAreTolerated() {
        HostListParser.Result w = HostListParser.parse(
                "  0.0.0.0\tspaced.example.com  \r\n0.0.0.0 tabbed.example.com");
        assertTrue(w.set().matches("spaced.example.com"));
        assertTrue(w.set().matches("tabbed.example.com"));
        assertEquals(0, w.malformed());
    }
}