package tracewall.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuthErrorCodeTest {
    @Test public void invalidCredentialsAreNotAnEmailExistenceOracle() {
        assertEquals(AuthErrorCode.Reason.INVALID_CREDENTIALS, AuthErrorCode.classify("ERROR_WRONG_PASSWORD"));
        assertEquals(AuthErrorCode.Reason.INVALID_CREDENTIALS, AuthErrorCode.classify("ERROR_USER_NOT_FOUND"));
        assertEquals(AuthErrorCode.Reason.INVALID_CREDENTIALS, AuthErrorCode.classify("ERROR_INVALID_LOGIN_CREDENTIALS"));
        assertEquals(AuthErrorCode.Reason.INVALID_CREDENTIALS, AuthErrorCode.classify("ERROR_INVALID_EMAIL"));
    }

    @Test public void everyCollisionUsesNoMergePolicy() {
        for (String code : new String[] { "ERROR_CREDENTIAL_ALREADY_IN_USE", "ERROR_EMAIL_ALREADY_IN_USE",
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" }) {
            assertEquals(AuthErrorCode.Reason.COLLISION, AuthErrorCode.classify(code));
            assertFalse(AccountPolicy.mayReplaceIdentityAfterCollision());
        }
    }

    @Test public void configurationAndDisabledProvidersAreDistinct() {
        assertEquals(AuthErrorCode.Reason.PROVIDER_DISABLED, AuthErrorCode.classify("ERROR_OPERATION_NOT_ALLOWED"));
        assertEquals(AuthErrorCode.Reason.CONFIGURATION, AuthErrorCode.classify("ERROR_INVALID_APP_CREDENTIAL"));
        assertEquals(AuthErrorCode.Reason.CONFIGURATION, AuthErrorCode.classify("ERROR_INVALID_API_KEY"));
        assertEquals(AuthErrorCode.Reason.CONFIGURATION, AuthErrorCode.classify("ERROR_APP_NOT_AUTHORIZED"));
    }

    @Test public void commonRecoverableErrorsAreMappedWithoutRawExceptionText() {
        assertEquals(AuthErrorCode.Reason.NETWORK, AuthErrorCode.classify("ERROR_NETWORK_REQUEST_FAILED"));
        assertEquals(AuthErrorCode.Reason.RATE_LIMIT, AuthErrorCode.classify("ERROR_TOO_MANY_REQUESTS"));
        assertEquals(AuthErrorCode.Reason.RATE_LIMIT, AuthErrorCode.classify("ERROR_QUOTA_EXCEEDED"));
        assertEquals(AuthErrorCode.Reason.WEAK_PASSWORD, AuthErrorCode.classify("ERROR_WEAK_PASSWORD"));
        assertEquals(AuthErrorCode.Reason.RECENT_LOGIN, AuthErrorCode.classify("ERROR_REQUIRES_RECENT_LOGIN"));
        assertEquals(AuthErrorCode.Reason.ACCOUNT_DISABLED, AuthErrorCode.classify("ERROR_USER_DISABLED"));
    }

    @Test public void unexpectedCodesUseSafeFallback() {
        assertEquals(AuthErrorCode.Reason.UNKNOWN, AuthErrorCode.classify(null));
        assertEquals(AuthErrorCode.Reason.UNKNOWN, AuthErrorCode.classify("untrusted SDK detail"));
    }

    @Test public void resetDoesNotRevealMissingDisabledOrOtherAccountSpecificState() {
        for (String code : new String[] { null, "ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED",
                "ERROR_INVALID_EMAIL", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS",
                "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_REQUIRES_RECENT_LOGIN", "unexpected" }) {
            assertFalse(AuthErrorCode.mayReportResetFailure(code));
        }
        assertTrue(AuthErrorCode.mayReportResetFailure("ERROR_NETWORK_REQUEST_FAILED"));
        assertTrue(AuthErrorCode.mayReportResetFailure("ERROR_TOO_MANY_REQUESTS"));
        assertTrue(AuthErrorCode.mayReportResetFailure("ERROR_OPERATION_NOT_ALLOWED"));
        assertTrue(AuthErrorCode.mayReportResetFailure("ERROR_INVALID_API_KEY"));
    }
}
