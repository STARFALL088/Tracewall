package tracewall.auth;

/** Contains no password, credential, email token, or account secret. */
final class AuthUiState {
    final boolean configured;
    final boolean identityMismatch;
    final boolean boundIdentity;
    final boolean hasUser;
    final boolean anonymous;
    final boolean emailLinked;
    final boolean hasEmail;
    final boolean emailVerified;
    final String email;
    final boolean busy;
    final boolean createEmail;
    final int message;
    final long successSequence;
    final boolean autoFinish;

    AuthUiState(boolean configured, boolean identityMismatch, boolean boundIdentity, boolean hasUser, boolean anonymous,
                boolean emailLinked, boolean hasEmail, boolean emailVerified, String email, boolean busy,
                boolean createEmail, int message, long successSequence, boolean autoFinish) {
        this.configured = configured;
        this.identityMismatch = identityMismatch;
        this.boundIdentity = boundIdentity;
        this.hasUser = hasUser;
        this.anonymous = anonymous;
        this.emailLinked = emailLinked;
        this.hasEmail = hasEmail;
        this.emailVerified = emailVerified;
        this.email = email;
        this.busy = busy;
        this.createEmail = createEmail;
        this.message = message;
        this.successSequence = successSequence;
        this.autoFinish = autoFinish;
    }
}
