package tracewall.auth;

import java.util.Objects;
import java.util.Set;

/** Identity decisions, deliberately independent of Android and Firebase. */
public final class AccountPolicy {
    public static final String CHILD = "child";
    public static final String PARENT = "parent";
    public static final String EMAIL = "password";
    public static final String PHONE = "phone";

    public enum Action { SIGN_IN, CREATE_EMAIL, LINK, ALREADY_CONNECTED }

    private AccountPolicy() { }

    public static Action action(String currentUid, Set<String> providers,
                                String provider, boolean createEmail) {
        if (currentUid != null) {
            return providers != null && providers.contains(provider)
                    ? Action.ALREADY_CONNECTED : Action.LINK;
        }
        return createEmail && EMAIL.equals(provider) ? Action.CREATE_EMAIL : Action.SIGN_IN;
    }

    /** A flow cannot migrate to an identity that appeared/changed while a chooser was open. */
    public static boolean sameIdentity(String expectedUid, String currentUid) {
        return Objects.equals(expectedUid, currentUid);
    }

    public static boolean successfulIdentity(String expectedUid, String resultUid, String currentUid) {
        return successfulIdentity(expectedUid, resultUid, currentUid, null);
    }

    public static boolean successfulIdentity(String expectedUid, String resultUid, String currentUid,
                                              String boundUid) {
        return resultUid != null && resultUid.equals(currentUid)
                && (expectedUid == null || expectedUid.equals(resultUid))
                && currentMatchesBound(boundUid, resultUid);
    }

    public static boolean currentMatchesBound(String boundUid, String currentUid) {
        return boundUid == null || boundUid.isEmpty() || currentUid == null || boundUid.equals(currentUid);
    }

    /** Verify an isolated recovery candidate BEFORE making it the primary SDK principal. */
    public static boolean canPromoteRecovery(String boundUid, String candidateUid, String currentUid) {
        return currentUid == null && boundUid != null && !boundUid.isEmpty() && boundUid.equals(candidateUid);
    }

    public static boolean canCreateGuest(String currentUid, String boundUid) {
        return currentUid == null && (boundUid == null || boundUid.isEmpty());
    }

    /** No merge, sign-out, delete, or replacement is a legal collision recovery. */
    public static boolean mayReplaceIdentityAfterCollision() { return false; }

    public static boolean canSignOut(String role, String uid, boolean anonymous) {
        // Offline cloud queues and principal-local profiles require a separate quiesced migration design.
        return false;
    }

    /** Missing/unknown callers get the more restrictive Child capabilities. */
    public static String role(String value) { return PARENT.equals(value) ? PARENT : CHILD; }
}
