package tracewall.auth;

/** Allow-list error classification. Never display SDK messages, credentials, or tokens. */
final class AuthErrorCode {
    enum Reason {
        INVALID_CREDENTIALS, COLLISION, PROVIDER_DISABLED, CONFIGURATION, NETWORK,
        RATE_LIMIT, WEAK_PASSWORD, RECENT_LOGIN, ACCOUNT_DISABLED, UNKNOWN
    }

    private AuthErrorCode() { }

    /** Password reset only exposes transport/project failures, never account-specific existence. */
    static boolean mayReportResetFailure(String code) {
        Reason reason = classify(code);
        return reason == Reason.NETWORK || reason == Reason.RATE_LIMIT
                || reason == Reason.CONFIGURATION || reason == Reason.PROVIDER_DISABLED;
    }

    static Reason classify(String code) {
        if (code == null) return Reason.UNKNOWN;
        switch (code) {
            case "ERROR_INVALID_CREDENTIAL":
            case "ERROR_INVALID_LOGIN_CREDENTIALS":
            case "ERROR_WRONG_PASSWORD":
            case "ERROR_USER_NOT_FOUND":
            case "ERROR_INVALID_EMAIL":
                return Reason.INVALID_CREDENTIALS;
            case "ERROR_CREDENTIAL_ALREADY_IN_USE":
            case "ERROR_EMAIL_ALREADY_IN_USE":
            case "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL":
                return Reason.COLLISION;
            case "ERROR_OPERATION_NOT_ALLOWED":
                return Reason.PROVIDER_DISABLED;
            case "ERROR_APP_NOT_AUTHORIZED":
            case "ERROR_INVALID_API_KEY":
            case "ERROR_MISSING_CLIENT_IDENTIFIER":
            case "ERROR_MISSING_APP_CREDENTIAL":
            case "ERROR_INVALID_APP_CREDENTIAL":
            case "ERROR_WEB_INTERNAL_ERROR":
                return Reason.CONFIGURATION;
            case "ERROR_NETWORK_REQUEST_FAILED":
            case "ERROR_WEB_NETWORK_REQUEST_FAILED":
                return Reason.NETWORK;
            case "ERROR_TOO_MANY_REQUESTS":
            case "ERROR_QUOTA_EXCEEDED":
                return Reason.RATE_LIMIT;
            case "ERROR_WEAK_PASSWORD":
                return Reason.WEAK_PASSWORD;
            case "ERROR_REQUIRES_RECENT_LOGIN":
                return Reason.RECENT_LOGIN;
            case "ERROR_USER_DISABLED":
                return Reason.ACCOUNT_DISABLED;
            default:
                return Reason.UNKNOWN;
        }
    }
}
