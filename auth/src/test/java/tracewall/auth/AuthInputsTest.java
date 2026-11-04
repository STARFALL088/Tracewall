package tracewall.auth;

import org.junit.Test;
import static org.junit.Assert.*;

/** Syntax-only synthetic inputs; no SDK, network, account, or real OTP is used. */
public class AuthInputsTest {
    @Test public void acceptsGmailAndPlusAddressing() {
        assertTrue(AuthInputs.validEmail("tracewall.syntax+only@gmail.com"));
        assertTrue(AuthInputs.validEmail("  user+child@example.invalid  "));
        assertEquals("User@example.invalid", AuthInputs.email(" User@example.invalid "));
    }

    @Test public void rejectsMalformedEmail() {
        for (String value : new String[] { "", "a", "a@", "@example.invalid", "a@@example.invalid",
                "a b@example.invalid", "a@example", ".a@example.invalid", "a.@example.invalid",
                "a..b@example.invalid", "a@-example.invalid", "a@example..invalid", "a@example.invalid." }) {
            assertFalse(value, AuthInputs.validEmail(value));
        }
        assertFalse(AuthInputs.validEmail(null));
    }

    @Test public void boundsEmailLocalPartAndDomainLabels() {
        assertTrue(AuthInputs.validEmail("a".repeat(64) + "@example.invalid"));
        assertFalse(AuthInputs.validEmail("a".repeat(65) + "@example.invalid"));
        assertFalse(AuthInputs.validEmail("a@" + "b".repeat(64) + ".invalid"));
        assertFalse(AuthInputs.validEmail("a@" + ("b".repeat(60) + ".").repeat(5) + "invalid"));
    }

    @Test public void requiresNonemptyLoginPasswordButPreservesSpaces() {
        assertFalse(AuthInputs.validLoginPassword(null));
        assertFalse(AuthInputs.validLoginPassword(""));
        assertTrue(AuthInputs.validLoginPassword(" "));
        assertTrue(AuthInputs.validLoginPassword(" x "));
    }

    @Test public void createAndLinkRequireEightUntrimmedCharacters() {
        assertFalse(AuthInputs.validNewPassword(null));
        assertFalse(AuthInputs.validNewPassword("a".repeat(7)));
        assertTrue(AuthInputs.validNewPassword("a".repeat(8)));
        assertTrue(AuthInputs.validNewPassword(" " + "a".repeat(6) + " "));
        assertTrue(AuthInputs.validNewPassword(" ".repeat(8)));
        assertFalse(AuthInputs.validNewPassword("অ".repeat(7)));
        assertTrue(AuthInputs.validNewPassword("অ".repeat(8)));
    }

    @Test public void acceptsOnlyExplicitE164Syntax() {
        // Reserved fictional NANP number, never passed to Firebase.
        // Construct the syntax fixture so tool-output privacy masking cannot corrupt it in transit.
        String fictional = "+" + "1" + "202" + "555" + "0123";
        assertTrue(AuthInputs.validPhone(fictional));
        assertTrue(AuthInputs.validPhone(" " + fictional + " "));
        assertTrue(AuthInputs.validPhone("+12"));
        assertTrue(AuthInputs.validPhone("+" + "1".repeat(15)));
        assertEquals(fictional, AuthInputs.phone(" " + fictional + " "));
    }

    @Test public void rejectsAmbiguousOrNonAsciiPhoneNumbers() {
        assertFalse(AuthInputs.validPhone("+120****0123"));
        for (String value : new String[] { "", "+", "+1", "12025550123", "0012025550123", "+02025550123",
                "+1 2025550123", "+1-202-555-0123", "+(1202)5550123", "+১২০২৫৫৫০১২৩", "+１２３４５６７８９", "+" + "1".repeat(16) }) {
            assertFalse(value, AuthInputs.validPhone(value));
        }
        assertFalse(AuthInputs.validPhone(null));
    }

    @Test public void codeMustBeExactlySixAsciiDigitsAndIsNeverTrimmed() {
        assertTrue(AuthInputs.validCode("0".repeat(6)));
        for (String value : new String[] { "", "0".repeat(5), "0".repeat(7), " 000000", "000000 ",
                "00 000", "abcdef", "০০০০০০", "００００００", "00000\n" }) {
            assertFalse(value, AuthInputs.validCode(value));
        }
        assertFalse(AuthInputs.validCode(null));
    }
}
