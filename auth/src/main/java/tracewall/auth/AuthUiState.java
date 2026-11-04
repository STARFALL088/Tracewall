package tracewall.auth;

/** Contains no password, OTP, credential, verification ID, or resending token. */
final class AuthUiState {
    final boolean configured;
    final boolean identityMismatch;
    final boolean boundIdentity;
    final boolean hasUser;
    final boolean anonymous;
    final boolean emailLinked;
    final boolean googleLinked;
    final boolean phoneLinked;
    final boolean hasEmail;
    final boolean emailVerified;
    final String email;
    final boolean busy;
    final boolean phoneActive;
    final boolean awaitingCode;
    final boolean canResend;
    final boolean canCancelPhone;
    final long cooldownSeconds;
    final boolean consent;
    final String phone;
    final boolean createEmail;
    final int message;
    final long successSequence;
    final boolean autoFinish;

    AuthUiState(boolean configured, boolean identityMismatch, boolean boundIdentity, boolean hasUser, boolean anonymous,
                boolean emailLinked, boolean googleLinked, boolean phoneLinked,
                boolean hasEmail, boolean emailVerified, String email, boolean busy,
                boolean phoneActive, boolean awaitingCode, boolean canResend,
                boolean canCancelPhone, long cooldownSeconds, boolean consent, String phone,
                boolean createEmail, int message, long successSequence, boolean autoFinish) {
        this.configured = configured;
        this.identityMismatch = identityMismatch;
        this.boundIdentity = boundIdentity;
        this.hasUser = hasUser;
        this.anonymous = anonymous;
        this.emailLinked = emailLinked;
        this.googleLinked = googleLinked;
        this.phoneLinked = phoneLinked;
        this.hasEmail = hasEmail;
        this.emailVerified = emailVerified;
        this.email = email;
        this.busy = busy;
        this.phoneActive = phoneActive;
        this.awaitingCode = awaitingCode;
        this.canResend = canResend;
        this.canCancelPhone = canCancelPhone;
        this.cooldownSeconds = cooldownSeconds;
        this.consent = consent;
        this.phone = phone;
        this.createEmail = createEmail;
        this.message = message;
        this.successSequence = successSequence;
        this.autoFinish = autoFinish;
    }
}
