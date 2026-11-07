package tracewall.auth;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuthException;

final class AuthErrors {
    private AuthErrors() { }

    static int message(Exception error, boolean linking) {
        if (error instanceof BoundAccountRecovery.Failure) {
            BoundAccountRecovery.Reason reason = ((BoundAccountRecovery.Failure) error).reason;
            return reason == BoundAccountRecovery.Reason.WRONG_IDENTITY ? R.string.auth_error_bound_candidate
                : reason == BoundAccountRecovery.Reason.BUSY ? R.string.auth_wait_to_leave
                : R.string.auth_error_identity_changed;
        }
        if (error instanceof FirebaseNetworkException) return R.string.auth_error_network;
        if (error instanceof FirebaseTooManyRequestsException) return R.string.auth_error_rate_limit;
        AuthErrorCode.Reason reason = AuthErrorCode.classify(error instanceof FirebaseAuthException
                ? ((FirebaseAuthException) error).getErrorCode() : null);
        switch (reason) {
            case INVALID_CREDENTIALS: return R.string.auth_error_credentials;
            case COLLISION: return linking ? R.string.auth_error_link_collision : R.string.auth_error_collision;
            case PROVIDER_DISABLED: return R.string.auth_error_provider_disabled;
            case CONFIGURATION: return R.string.auth_error_setup;
            case NETWORK: return R.string.auth_error_network;
            case RATE_LIMIT: return R.string.auth_error_rate_limit;
            case WEAK_PASSWORD: return R.string.auth_error_weak_password;
            case RECENT_LOGIN: return R.string.auth_error_recent_login;
            case ACCOUNT_DISABLED: return R.string.auth_error_disabled;
            default: return R.string.auth_error_generic;
        }
    }
}
