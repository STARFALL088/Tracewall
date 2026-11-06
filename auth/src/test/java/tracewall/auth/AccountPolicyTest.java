package tracewall.auth;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class AccountPolicyTest {
    private static final Set<String> NONE = Set.of();

    @Test public void signedOutEmailCanSignInOrCreate() {
        assertEquals(AccountPolicy.Action.SIGN_IN, AccountPolicy.action(null, NONE, AccountPolicy.EMAIL, false));
        assertEquals(AccountPolicy.Action.CREATE_EMAIL, AccountPolicy.action(null, NONE, AccountPolicy.EMAIL, true));
    }

    @Test public void signedOutPhoneNeverCreatesEmail() {
        assertEquals(AccountPolicy.Action.SIGN_IN,
                AccountPolicy.action(null, NONE, AccountPolicy.PHONE, true));
    }

    @Test public void everyExistingUidLinksRegardlessOfInitialEmailChoice() {
        for (String uid : new String[] { "synthetic-guest-uid", "synthetic-registered-uid" }) {
            for (String provider : new String[] { AccountPolicy.EMAIL, AccountPolicy.PHONE }) {
                assertEquals(AccountPolicy.Action.LINK, AccountPolicy.action(uid, NONE, provider, false));
                assertEquals(AccountPolicy.Action.LINK, AccountPolicy.action(uid, NONE, provider, true));
            }
        }
    }

    @Test public void alreadyLinkedProviderIsNotReplaced() {
        for (String provider : new String[] { AccountPolicy.EMAIL, AccountPolicy.PHONE }) {
            assertEquals(AccountPolicy.Action.ALREADY_CONNECTED,
                    AccountPolicy.action("synthetic-uid", Set.of(provider), provider, false));
        }
    }

    @Test public void differentProviderCanLinkWithoutChangingExistingProvider() {
        assertEquals(AccountPolicy.Action.LINK,
                AccountPolicy.action("synthetic-uid", Set.of(AccountPolicy.EMAIL), AccountPolicy.PHONE, false));
    }

    @Test public void phoneCallbackCannotAdoptAnUnexpectedIdentity() {
        assertTrue(AccountPolicy.sameIdentity(null, null));
        assertTrue(AccountPolicy.sameIdentity("old", "old"));
        assertFalse(AccountPolicy.sameIdentity(null, "new"));
        assertFalse(AccountPolicy.sameIdentity("old", null));
        assertFalse(AccountPolicy.sameIdentity("old", "new"));
    }

    @Test public void initialSignInNeedsActualResultAndCurrentUidAgreement() {
        assertTrue(AccountPolicy.successfulIdentity(null, "new", "new"));
        assertFalse(AccountPolicy.successfulIdentity(null, null, null));
        assertFalse(AccountPolicy.successfulIdentity(null, "new", "other"));
    }

    @Test public void upgradeMustKeepOriginalUidAndRejectUnexpectedSuccessfulResults() {
        assertTrue(AccountPolicy.successfulIdentity("old", "old", "old"));
        assertFalse(AccountPolicy.successfulIdentity("old", "new", "new"));
        assertFalse(AccountPolicy.successfulIdentity("old", "old", "new"));
        assertFalse(AccountPolicy.successfulIdentity("old", null, "old"));
    }

    @Test public void collisionNeverPermitsReplacementOrAutomaticMerge() {
        assertFalse(AccountPolicy.mayReplaceIdentityAfterCollision());
        assertEquals(AccountPolicy.Action.LINK, AccountPolicy.action("old", NONE, AccountPolicy.PHONE, false));
    }

    @Test public void noNormalPrimarySignOutForEitherRoleEvenRegisteredParent() {
        assertFalse(AccountPolicy.canSignOut(AccountPolicy.PARENT, "uid", false));
        assertFalse(AccountPolicy.canSignOut(AccountPolicy.PARENT, "uid", true));
        assertFalse(AccountPolicy.canSignOut(AccountPolicy.PARENT, null, false));
        assertFalse(AccountPolicy.canSignOut(AccountPolicy.CHILD, "uid", false));
        assertFalse(AccountPolicy.canSignOut(AccountPolicy.CHILD, "uid", true));
        assertFalse(AccountPolicy.canSignOut("unknown", "uid", false));
        assertFalse(AccountPolicy.canSignOut(null, "uid", false));
    }

    @Test public void successfulRecoveryMustMatchExactBoundUid() {
        assertTrue(AccountPolicy.successfulIdentity(null, "original", "original", "original"));
        assertFalse(AccountPolicy.successfulIdentity(null, "other", "other", "original"));
        assertFalse(AccountPolicy.successfulIdentity(null, "original", "original", " original "));
        assertTrue(AccountPolicy.successfulIdentity("original", "original", "original", "original"));
        assertFalse(AccountPolicy.successfulIdentity("original", "other", "other", "original"));
    }

    @Test public void isolatedRecoveryOnlyPromotesTheExactAnchorIntoANullPrimarySession() {
        assertTrue(AccountPolicy.canPromoteRecovery("original", "original", null));
        assertFalse(AccountPolicy.canPromoteRecovery("original", "other", null));
        assertFalse(AccountPolicy.canPromoteRecovery("original", "original", "original"));
        assertFalse(AccountPolicy.canPromoteRecovery("original", "original", "other"));
        assertFalse(AccountPolicy.canPromoteRecovery("original", null, null));
        assertFalse(AccountPolicy.canPromoteRecovery(null, "other", null));
        assertFalse(AccountPolicy.canPromoteRecovery("", "other", null));
        assertFalse(AccountPolicy.canPromoteRecovery(" original ", "original", null));
    }

    @Test public void mismatchedExistingIdentityIsBlockedNotSignedOut() {
        assertFalse(AccountPolicy.currentMatchesBound("original", "other"));
        assertFalse(AccountPolicy.canPromoteRecovery("original", "original", "other"));
        assertFalse(AccountPolicy.canSignOut(AccountPolicy.PARENT, "other", false));
        assertTrue(AccountPolicy.currentMatchesBound("original", "original"));
        assertTrue(AccountPolicy.currentMatchesBound("original", null));
    }

    @Test public void boundGuestCanOnlyRecoverNeverCreateAnotherAnonymousUid() {
        assertFalse(AccountPolicy.canCreateGuest(null, "original-guest"));
        assertFalse(AccountPolicy.canCreateGuest("original-guest", "original-guest"));
        assertFalse(AccountPolicy.canCreateGuest("existing", null));
        assertTrue(AccountPolicy.canCreateGuest(null, null));
        assertTrue(AccountPolicy.canCreateGuest(null, ""));
    }

    @Test public void optionalAnchorDoesNotAffectNewInstallationSignIn() {
        assertTrue(AccountPolicy.currentMatchesBound(null, "new"));
        assertTrue(AccountPolicy.currentMatchesBound("", "new"));
        assertTrue(AccountPolicy.successfulIdentity(null, "new", "new", null));
        assertTrue(AccountPolicy.successfulIdentity(null, "new", "new", ""));
    }

    @Test public void invalidRoleDefaultsToRestrictiveChild() {
        assertEquals(AccountPolicy.PARENT, AccountPolicy.role("parent"));
        assertEquals(AccountPolicy.CHILD, AccountPolicy.role("child"));
        assertEquals(AccountPolicy.CHILD, AccountPolicy.role("PARENT"));
        assertEquals(AccountPolicy.CHILD, AccountPolicy.role(null));
    }
}
