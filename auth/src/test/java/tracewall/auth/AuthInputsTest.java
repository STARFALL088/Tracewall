package tracewall.auth;

import org.junit.Test;
import static org.junit.Assert.*;

/** Syntax-only synthetic inputs; no SDK, network, account, or credential is used. */
public class AuthInputsTest {
    @Test public void acceptsPlusAddressingAndTrimsIdentifier() {
        assertTrue(AuthInputs.validEmail("tracewall.syntax+only@example.invalid"));
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
}
