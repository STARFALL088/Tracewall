package tracewall.auth;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * All state and Firebase task ownership survive rotation, but not process death.
 * Never put an Activity, password, ID token, or AuthCredential in saved state.
 */
public final class AuthViewModel extends AndroidViewModel {
    private final MutableLiveData<AuthUiState> states = new MutableLiveData<>();
    private final Executor main;
    private final AuthFlowGuard work = new AuthFlowGuard();
    private final FirebaseAuth.AuthStateListener authListener = ignored -> onAuthStateChanged();
    private FirebaseAuth auth;
    private int message;
    private boolean cleared;
    private boolean initialized;
    private boolean initial;
    private boolean launchedWithoutUser;
    private boolean createEmail;
    private String role = AccountPolicy.CHILD;
    private String boundUid;
    private long successSequence;
    private String successfulUid;
    private boolean autoFinish;

    public AuthViewModel(@NonNull Application application) {
        super(application);
        main = ContextCompat.getMainExecutor(application);
        try {
            auth = FirebaseAuth.getInstance();
            auth.useAppLanguage();
            launchedWithoutUser = auth.getCurrentUser() == null;
            auth.addAuthStateListener(authListener);
        } catch (IllegalStateException | IllegalArgumentException notConfigured) {
            auth = null;
            launchedWithoutUser = true;
            message = R.string.auth_error_setup;
        }
        publish();
    }

    void initialize(String requestedRole, boolean requestedInitial, String requestedBoundUid) {
        if (initialized) return;
        initialized = true;
        role = AccountPolicy.role(requestedRole);
        initial = requestedInitial;
        // UIDs are opaque, exact identifiers. Never trim or repair an installation anchor.
        boundUid = requestedBoundUid == null || requestedBoundUid.isEmpty() ? null : requestedBoundUid;
        publish();
    }

    LiveData<AuthUiState> states() { return states; }

    void setCreateEmail(boolean create) {
        if (work.busy()) return;
        if (boundUid != null && create) { show(R.string.auth_bound_recovery); return; }
        createEmail = create;
        publish();
    }

    private FirebaseUser user() { return auth == null ? null : auth.getCurrentUser(); }
    private String uid() { return user() == null ? null : user().getUid(); }

    private static Set<String> providers(FirebaseUser user) {
        Set<String> providers = new HashSet<>();
        if (user != null) for (UserInfo info : user.getProviderData()) providers.add(info.getProviderId());
        return providers;
    }

    private boolean available() {
        if (auth == null) {
            show(R.string.auth_error_setup);
            return false;
        }
        if (!AccountPolicy.currentMatchesBound(boundUid, uid())) {
            show(R.string.auth_error_bound_existing);
            return false;
        }
        return !cleared && !work.busy();
    }

    private long begin() {
        long token = work.begin();
        if (token == 0 || cleared) return 0;
        message = 0;
        publish();
        return token;
    }

    private boolean finishWork(long token) {
        if (!work.finish(token)) return false;
        return true;
    }

    void show(int text) { message = text; publish(); }

    private boolean identityMatches(String expectedUid) {
        return AccountPolicy.sameIdentity(expectedUid, uid())
                && AccountPolicy.currentMatchesBound(boundUid, uid());
    }

    private void identityError(long token) {
        finishWork(token);
        show(R.string.auth_error_identity_changed);
    }

    void submitEmail(String rawEmail, String password) {
        if (!available()) return;
        String email = AuthInputs.email(rawEmail);
        if (!AuthInputs.validEmail(email)) { show(R.string.auth_error_email); return; }
        FirebaseUser current = user();
        AccountPolicy.Action action = AccountPolicy.action(uid(), providers(current), AccountPolicy.EMAIL, createEmail);
        if (boundUid != null && action == AccountPolicy.Action.CREATE_EMAIL) {
            show(R.string.auth_bound_recovery);
            return;
        }
        if (action == AccountPolicy.Action.ALREADY_CONNECTED) { show(R.string.auth_already_connected); return; }
        boolean newPassword = action != AccountPolicy.Action.SIGN_IN;
        if (newPassword ? !AuthInputs.validNewPassword(password) : !AuthInputs.validLoginPassword(password)) {
            show(newPassword ? R.string.auth_error_password_short : R.string.auth_error_password_empty);
            return;
        }
        String expectedUid = uid();
        long token = begin();
        if (token == 0) return;
        try {
            // Keep the check immediately next to SDK issuance, never silently turn a sign-in into a switch.
            if (!identityMatches(expectedUid)) { identityError(token); return; }
            Task<AuthResult> task;
            if (action == AccountPolicy.Action.LINK) {
                task = current.linkWithCredential(EmailAuthProvider.getCredential(email, password));
            } else if (action == AccountPolicy.Action.CREATE_EMAIL) {
                if (user() != null) { identityError(token); return; }
                task = auth.createUserWithEmailAndPassword(email, password);
            } else {
                if (user() != null) { identityError(token); return; }
                task = boundUid == null ? auth.signInWithEmailAndPassword(email, password)
                    : recover(EmailAuthProvider.getCredential(email, password), token);
            }
            observeAuthentication(task, token, expectedUid);
        } catch (RuntimeException failure) {
            failAuthentication(token, expectedUid, failure);
        }
    }

    /** Explicit reauthentication only; linked email is never overwritten. */
    void confirmEmailPassword(String password) {
        if (!available()) return;
        FirebaseUser current = user();
        if (current == null || !providers(current).contains(AccountPolicy.EMAIL)
                || current.getEmail() == null) { show(R.string.auth_already_connected); return; }
        if (!AuthInputs.validLoginPassword(password)) { show(R.string.auth_error_password_empty); return; }
        String expectedUid = current.getUid();
        long token = begin();
        if (token == 0) return;
        try {
            if (!identityMatches(expectedUid)) { identityError(token); return; }
            current.reauthenticate(EmailAuthProvider.getCredential(current.getEmail(), password))
                    .addOnCompleteListener(main, task -> {
                        if (cleared || !work.owns(token)) return;
                        if (!identityMatches(expectedUid)) { identityError(token); return; }
                        finishWork(token);
                        show(task.isSuccessful() ? R.string.auth_reauthenticated
                                : AuthErrors.message(task.getException(), true));
                    });
        } catch (RuntimeException failure) {
            finishWork(token);
            show(AuthErrors.message(failure, true));
        }
    }

    void guest() {
        if (!available()) return;
        if (!AccountPolicy.canCreateGuest(uid(), boundUid)) {
            show(boundUid != null ? R.string.auth_bound_recovery : R.string.auth_error_identity_changed);
            return;
        }
        long token = begin();
        if (token == 0) return;
        try {
            if (user() != null) { identityError(token); return; }
            observeAuthentication(auth.signInAnonymously(), token, null);
        } catch (RuntimeException failure) {
            failAuthentication(token, null, failure);
        }
    }

    private void observeAuthentication(Task<AuthResult> task, long token, String expectedUid) {
        task.addOnCompleteListener(main, result -> {
            if (!work.owns(token)) return;
            FirebaseUser authenticated = result.isSuccessful() && result.getResult() != null
                    ? result.getResult().getUser() : null;
            String resultUid = authenticated == null ? null : authenticated.getUid();
            if (cleared) return;
            if (!result.isSuccessful()) {
                failAuthentication(token, expectedUid, result.getException());
                return;
            }
            if (!AccountPolicy.successfulIdentity(expectedUid,
                    resultUid, uid(), boundUid)) {
                identityError(token);
                return;
            }
            finishWork(token);
            successfulUid = authenticated.getUid();
            ++successSequence;
            autoFinish = initial && launchedWithoutUser && expectedUid == null;
            show(expectedUid == null ? R.string.auth_success : R.string.auth_link_success);
        });
    }

    private Task<AuthResult> recover(AuthCredential credential, long token) {
        return BoundAccountRecovery.signIn(auth, credential, boundUid,
            () -> !cleared && work.owns(token) && identityMatches(null), main);
    }

    private void failAuthentication(long token, String expectedUid, Exception failure) {
        if (cleared || !work.owns(token)) return;
        finishWork(token);
        if (!identityMatches(expectedUid)) {
            show(R.string.auth_error_identity_changed);
            return;
        }
        show(AuthErrors.message(failure, expectedUid != null));
    }

    void resetPassword(String rawEmail) {
        if (!available()) return;
        String email = AuthInputs.email(rawEmail);
        if (!AuthInputs.validEmail(email)) { show(R.string.auth_error_email); return; }
        long token = begin();
        if (token == 0) return;
        try {
            auth.sendPasswordResetEmail(email).addOnCompleteListener(main, task -> {
                if (cleared || !finishWork(token)) return;
                Exception error = task.getException();
                String code = error instanceof FirebaseAuthException ? ((FirebaseAuthException) error).getErrorCode() : "";
                // Old projects without enumeration protection must not reveal address existence either.
                boolean reportFailure = error instanceof FirebaseNetworkException
                        || error instanceof FirebaseTooManyRequestsException
                        || AuthErrorCode.mayReportResetFailure(code);
                show(!task.isSuccessful() && reportFailure ? AuthErrors.message(error, false) : R.string.auth_reset_sent);
            });
        } catch (RuntimeException failure) {
            finishWork(token);
            show(AuthErrors.message(failure, false));
        }
    }

    void verifyEmail(boolean reload) {
        if (!available()) return;
        FirebaseUser current = user();
        if (current == null || current.getEmail() == null) return;
        String expectedUid = current.getUid();
        long token = begin();
        if (token == 0) return;
        try {
            Task<Void> task = reload ? current.reload() : current.sendEmailVerification();
            task.addOnCompleteListener(main, result -> {
                if (cleared || !work.owns(token)) return;
                if (!identityMatches(expectedUid)) { identityError(token); return; }
                finishWork(token);
                show(!result.isSuccessful() ? AuthErrors.message(result.getException(), true)
                        : !reload ? R.string.auth_verification_sent
                        : user().isEmailVerified() ? R.string.auth_email_verified : R.string.auth_verification_pending);
            });
        } catch (RuntimeException failure) {
            finishWork(token);
            show(AuthErrors.message(failure, true));
        }
    }

    /** Firebase tasks cannot be cancelled safely; leave only after they finish. */
    boolean mayLeave() {
        if (work.busy()) { show(R.string.auth_wait_to_leave); return false; }
        return true;
    }

    private void onAuthStateChanged() {
        if (cleared) return;
        publish();
    }

    private void publish() {
        if (cleared) return;
        FirebaseUser current = user();
        Set<String> connected = providers(current);
        boolean emailPresent = current != null && current.getEmail() != null && !current.getEmail().isEmpty();
        boolean mismatch = !AccountPolicy.currentMatchesBound(boundUid, uid());
        boolean successful = !mismatch && current != null && current.getUid().equals(successfulUid);
        states.setValue(new AuthUiState(auth != null, mismatch, boundUid != null, current != null, current != null && current.isAnonymous(),
                connected.contains(AccountPolicy.EMAIL), emailPresent, current != null && current.isEmailVerified(),
                emailPresent ? current.getEmail() : "", work.busy(), createEmail,
                mismatch ? R.string.auth_error_bound_existing : message, successful ? successSequence : 0,
                successful && autoFinish));
    }

    @Override protected void onCleared() {
        cleared = true;
        if (auth != null) auth.removeAuthStateListener(authListener);
        super.onCleared();
    }
}
