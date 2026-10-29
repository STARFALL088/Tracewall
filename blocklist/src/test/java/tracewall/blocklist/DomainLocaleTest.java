package tracewall.blocklist;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.LOCALE)
class DomainLocaleTest {
    @Test
    void asciiPatternsNormalizeIndependentlyOfTheDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            for (Locale locale : new Locale[] {Locale.ROOT, Locale.forLanguageTag("tr-TR")}) {
                Locale.setDefault(locale);
                DomainPattern exact = DomainPattern.parse("  IMAGE.EXAMPLE.  ");
                DomainPattern wildcard = DomainPattern.parse("*.I.EXAMPLE");
                assertNotNull(exact, locale.toLanguageTag());
                assertNotNull(wildcard, locale.toLanguageTag());
                assertEquals("image.example", exact.text());
                assertEquals("*.i.example", wildcard.text());
                assertTrue(exact.matches("IMAGE.EXAMPLE."));
                assertTrue(wildcard.matches("SUB.I.EXAMPLE."));
                assertFalse(wildcard.matches("I.EXAMPLE"));
                assertFalse(wildcard.matches("SUB.NOTI.EXAMPLE"));
            }
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test
    void rulesLoadedBeforeLocaleChangesStillMatchAsciiQueries() {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.ROOT);
            DomainPattern exact = DomainPattern.parse("image.example");
            DomainPattern wildcard = DomainPattern.parse("*.i.example");
            DomainSet set = DomainSet.of(Arrays.asList("image.example", "*.i.example"));
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(exact.matches("IMAGE.EXAMPLE."));
            assertTrue(wildcard.matches("SUB.I.EXAMPLE."));
            assertTrue(set.matches("IMAGE.EXAMPLE."));
            assertTrue(set.matches("SUB.I.EXAMPLE."));
            assertFalse(set.matches("I.EXAMPLE"));
            assertFalse(set.matches("IMAGE.EXAMPLE.OTHER"));
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test
    void hostsAndPolicyUseTheSameLocaleIndependentNormalization() {
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            HostListParser.Result hosts = HostListParser.parse(
                    "0.0.0.0 IMAGE.EXAMPLE image.example\n127.0.0.1 *.I.EXAMPLE");
            assertEquals(2, hosts.entriesParsed());
            assertEquals(1, hosts.duplicates());
            assertEquals(0, hosts.malformed());
            PolicyEngine engine = new PolicyEngine(
                    DomainSet.of(Collections.singletonList("SAFE.I.EXAMPLE")),
                    Collections.singletonMap("ads", hosts.set()), Collections.emptySet());
            assertEquals(PolicyEngine.Verdict.BLOCK_CATEGORY, engine.decide("IMAGE.EXAMPLE", -1).verdict);
            assertEquals(PolicyEngine.Verdict.BLOCK_CATEGORY, engine.decide("SUB.I.EXAMPLE", -1).verdict);
            assertEquals(PolicyEngine.Verdict.ALLOW_LISTED, engine.decide("SAFE.I.EXAMPLE", -1).verdict);
            Locale.setDefault(Locale.ROOT);
            assertEquals(PolicyEngine.Verdict.ALLOW_LISTED, engine.decide("safe.i.example", -1).verdict);
            assertEquals(PolicyEngine.Verdict.BLOCK_CATEGORY, engine.decide("image.example", -1).verdict);
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }
}
