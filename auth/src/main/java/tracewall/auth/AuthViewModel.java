package tracewall.auth;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.auth.UserInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * All state and Firebase task ownership survive rotation, but not process death.
 * Never put an Activity, password, OTP, ID token, or AuthCredential in saved state.
 * Phone verification IDs/resend tokens live ONLY in this model's memory.
 */
public final class AuthViewModel extends AndroidViewModel {
    private enum Work { NONE, FIREBASE, PHONE_REQUEST }

    private final MutableLiveData<AuthUiState> states = new MutableLiveData<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor main;
    private final AuthFlowGuard work = new AuthFlowGuard();
    private final PhoneFlowGuard phoneFlow = new PhoneFlowGuard();
    private final FirebaseAuth.AuthStateListener authListener = ignored -> onAuthStateChanged();
    private FirebaseAuth auth;
    private Work workType = Work.NONE;
    private long workToken;
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

    private boolean consent;
    private String phone = "";
    private String phoneExpectedUid;
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private boolean phoneNeedsReattach;
    private long phoneSendToken;
    private long phoneRequestDeadline;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (cleared) return;
            long now = SystemClock.elapsedRealtime();
            if (workType == Work.PHONE_REQUEST && now >= phoneRequestDeadline) {
                finishWork(phoneSendToken);
                phoneFlow.stopListening(phoneFlow.generation());
                message = R.string.auth_phone_timeout;
            }
            publish();
            if (workType == Work.PHONE_REQUEST || phoneFlow.remainingSeconds(now) > 0) {
                handler.postDelayed(this, 1_000L);
            }
        }
    };

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
        if (work.busy() || phoneFlow.active()) return;
        if (boundUid != null && create) { show(R.string.auth_bound_recovery); return; }
        createEmail = create;
        publish();
    }

    void setConsent(boolean accepted) {
        if (work.busy() || phoneFlow.active()) return;
        consent = accepted;
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
        return !cleared && !work.busy() && !phoneFlow.active();
    }

    private long begin(Work type) {
        long token = work.begin();
        if (token == 0 || cleared) return 0;
        workType = type;
        workToken = token;
        message = 0;
        publish();
        return token;
    }

    private boolean finishWork(long token) {
        if (!work.finish(token)) return false;
        workType = Work.NONE;
        workToken = 0;
        return true;
    }

    void show(int text) { message = text; publish(); }

    private boolean identityMatches(String expectedUid) {
        return AccountPolicy.sameIdentity(expectedUid, uid())
                && AccountPolicy.currentMatchesBound(boundUid, uid());
    }

    private void identityError(long token) {
        finishWork(token);
        clearPhone();
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
        long token = begin(Work.FIREBASE);
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
            observeAuthentication(task, token, expectedUid, 0);
        } catch (RuntimeException failure) {
            failAuthentication(token, expectedUid, 0, failure);
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
        long token = begin(Work.FIREBASE);
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
        long token = begin(Work.FIREBASE);
        if (token == 0) return;
        try {
            if (user() != null) { identityError(token); return; }
            observeAuthentication(auth.signInAnonymously(), token, null, 0);
        } catch (RuntimeException failure) {
            failAuthentication(token, null, 0, failure);
        }
    }

    private void observeAuthentication(Task<AuthResult> task, long token, String expectedUid, long phoneGeneration) {
        task.addOnCompleteListener(main, result -> {
            if (!work.owns(token)) return;
            FirebaseUser authenticated = result.isSuccessful() && result.getResult() != null
                    ? result.getResult().getUser() : null;
            String resultUid = authenticated == null ? null : authenticated.getUid();
            if (cleared) return;
            if (!result.isSuccessful()) {
                failAuthentication(token, expectedUid, phoneGeneration, result.getException());
                return;
            }
            if (!AccountPolicy.successfulIdentity(expectedUid,
                    resultUid, uid(), boundUid)) {
                identityError(token);
                return;
            }
            finishWork(token);
            clearPhone();
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

    private void failAuthentication(long token, String expectedUid, long phoneGeneration, Exception failure) {
        if (cleared || !work.owns(token)) return;
        finishWork(token);
        if (!identityMatches(expectedUid)) {
            clearPhone();
            show(R.string.auth_error_identity_changed);
            return;
        }
        if (phoneGeneration != 0) phoneFlow.completionFailed(phoneGeneration);
        show(AuthErrors.message(failure, expectedUid != null));
    }

    private void useCredential(AuthCredential credential, String provider, String expectedUid,
                               long token, long phoneGeneration) {
        if (cleared || !work.owns(token)) return;
        if (!identityMatches(expectedUid)) { identityError(token); return; }
        FirebaseUser current = user();
        AccountPolicy.Action action = AccountPolicy.action(uid(), providers(current), provider, false);
        if (action == AccountPolicy.Action.ALREADY_CONNECTED) {
            finishWork(token);
            clearPhone();
            show(R.string.auth_already_connected);
            return;
        }
        workType = Work.FIREBASE; // Not cancellable by Activity teardown once Firebase owns the credential.
        publish();
        try {
            // This is the ONLY generic primary sign-in site. Existing UIDs always take the link branch.
            Task<AuthResult> task;
            if (current != null) {
                task = current.linkWithCredential(credential);
            } else {
                if (expectedUid != null || user() != null) { identityError(token); return; }
                task = boundUid == null ? auth.signInWithCredential(credential) : recover(credential, token);
            }
            observeAuthentication(task, token, expectedUid, phoneGeneration);
        } catch (RuntimeException failure) {
            failAuthentication(token, expectedUid, phoneGeneration, failure);
        }
    }

    void startPhone(Activity activity, String rawPhone) {
        if (!available()) return;
        if (!consent) { show(R.string.auth_error_consent); return; }
        if (!AuthInputs.validPhone(rawPhone)) { show(R.string.auth_error_phone); return; }
        if (providers(user()).contains(AccountPolicy.PHONE)) { show(R.string.auth_already_connected); return; }
        if (phoneFlow.remainingSeconds(SystemClock.elapsedRealtime()) > 0) {
            publish();
            return;
        }
        phone = AuthInputs.phone(rawPhone);
        phoneExpectedUid = uid();
        requestPhone(activity, null);
    }

    void resendPhone(Activity activity) {
        if (cleared || work.busy() || !phoneFlow.active() || resendToken == null) return;
        if (!consent) { show(R.string.auth_error_consent); return; }
        if (!identityMatches(phoneExpectedUid)) { identityError(0); return; }
        if (phoneFlow.remainingSeconds(SystemClock.elapsedRealtime()) > 0) return;
        requestPhone(activity, resendToken); // Force only for an explicit, cooldown-eligible resend.
    }

    private void requestPhone(Activity activity, PhoneAuthProvider.ForceResendingToken forceToken) {
        long now = SystemClock.elapsedRealtime();
        long generation = phoneFlow.start(now);
        if (generation == 0) return;
        verificationId = null;
        resendToken = null;
        phoneRequestDeadline = now + PhoneFlowGuard.TIMEOUT_MS;
        phoneSendToken = begin(Work.PHONE_REQUEST);
        if (phoneSendToken == 0) { clearPhone(); return; }
        message = R.string.auth_phone_sending;
        phoneNeedsReattach = false;
        issuePhone(activity, generation, forceToken);
        startTicks();
    }

    private void issuePhone(Activity activity, long generation, PhoneAuthProvider.ForceResendingToken forceToken) {
        if (cleared || !phoneFlow.owns(generation)) return;
        if (!identityMatches(phoneExpectedUid)) { identityError(phoneSendToken); return; }
        try {
            PhoneAuthOptions.Builder options = PhoneAuthOptions.newBuilder(auth)
                    .setPhoneNumber(phone)
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setActivity(activity)
                    .setCallbacks(phoneCallbacks(generation));
            if (forceToken != null) options.setForceResendingToken(forceToken);
            PhoneAuthProvider.verifyPhoneNumber(options.build());
        } catch (RuntimeException failure) {
            boolean linking = phoneExpectedUid != null;
            finishWork(phoneSendToken);
            clearPhone();
            show(AuthErrors.message(failure, linking));
        }
        publish();
    }

    private PhoneAuthProvider.OnVerificationStateChangedCallbacks phoneCallbacks(long generation) {
        // This callback captures only the ViewModel/generation, never the Activity used for binding.
        return new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            @Override public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                if (cleared || !phoneFlow.owns(generation)) return;
                finishWork(phoneSendToken);
                completePhoneCredential(credential, generation, true);
            }

            @Override public void onVerificationFailed(@NonNull FirebaseException error) {
                if (cleared || !phoneFlow.owns(generation) || workType == Work.FIREBASE) return;
                boolean linking = phoneExpectedUid != null;
                finishWork(phoneSendToken);
                clearPhone();
                show(AuthErrors.message(error, linking));
            }

            @Override public void onCodeSent(@NonNull String id, @NonNull PhoneAuthProvider.ForceResendingToken token) {
                if (cleared || !phoneFlow.owns(generation)) return;
                verificationId = id;
                resendToken = token;
                phoneFlow.sent(generation, SystemClock.elapsedRealtime());
                finishWork(phoneSendToken);
                if (workType != Work.FIREBASE) message = R.string.auth_code_sent;
                startTicks();
            }

            @Override public void onCodeAutoRetrievalTimeOut(@NonNull String id) {
                if (cleared || !phoneFlow.owns(generation)) return;
                verificationId = id;
                phoneFlow.stopListening(generation);
                finishWork(phoneSendToken);
                if (workType != Work.FIREBASE) message = R.string.auth_phone_timeout;
                publish();
            }
        };
    }

    void submitCode(String code) {
        if (cleared || work.busy() || !phoneFlow.active() || verificationId == null) return;
        if (!AuthInputs.validCode(code)) { show(R.string.auth_error_code); return; }
        try {
            completePhoneCredential(PhoneAuthProvider.getCredential(verificationId, code), phoneFlow.generation(), false);
        } catch (RuntimeException invalidCode) {
            show(R.string.auth_error_code);
        }
    }

    private void completePhoneCredential(PhoneAuthCredential credential, long generation, boolean automatic) {
        if (cleared || work.busy() || !phoneFlow.claimCompletion(generation, automatic)) return;
        long token = begin(Work.FIREBASE);
        if (token == 0) { phoneFlow.completionFailed(generation); return; }
        useCredential(credential, AccountPolicy.PHONE, phoneExpectedUid, token, generation);
    }

    void cancelPhone() {
        if (work.busy() && workType != Work.PHONE_REQUEST) return;
        finishWork(phoneSendToken);
        clearPhone();
        show(R.string.auth_phone_cancelled);
    }

    private void clearPhone() {
        phoneFlow.cancel();
        consent = false;
        verificationId = null;
        resendToken = null;
        phoneExpectedUid = null;
        phone = "";
        phoneNeedsReattach = false;
        phoneSendToken = 0;
    }

    void onHostStopped() {
        // Activity-bound Firebase callbacks are detached on stop by the SDK.
        if (phoneFlow.active()) phoneNeedsReattach = true;
    }

    void onHostStarted(Activity activity) {
        if (cleared || !phoneNeedsReattach || !phoneFlow.active()) return;
        phoneNeedsReattach = false;
        long generation = phoneFlow.generation();
        if (phoneFlow.canReattach(generation, SystemClock.elapsedRealtime())) {
            // SDK reentrant reattachment, not a resend: NEVER pass ForceResendingToken here.
            issuePhone(activity, generation, null);
        }
        startTicks();
    }

    private void startTicks() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void resetPassword(String rawEmail) {
        if (!available()) return;
        String email = AuthInputs.email(rawEmail);
        if (!AuthInputs.validEmail(email)) { show(R.string.auth_error_email); return; }
        long token = begin(Work.FIREBASE);
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
        long token = begin(Work.FIREBASE);
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

    /** Firebase tasks cannot be cancelled safely; phone sessions can be invalidated. */
    boolean mayLeave() {
        if (workType == Work.PHONE_REQUEST || (!work.busy() && phoneFlow.active())) cancelPhone();
        if (work.busy()) { show(R.string.auth_wait_to_leave); return false; }
        return true;
    }

    private void onAuthStateChanged() {
        if (cleared) return;
        if (!work.busy() && phoneFlow.active() && !identityMatches(phoneExpectedUid)) {
            clearPhone();
            message = R.string.auth_error_identity_changed;
        }
        publish();
    }

    private void publish() {
        if (cleared) return;
        FirebaseUser current = user();
        Set<String> connected = providers(current);
        long remaining = phoneFlow.remainingSeconds(SystemClock.elapsedRealtime());
        boolean emailPresent = current != null && current.getEmail() != null && !current.getEmail().isEmpty();
        boolean mismatch = !AccountPolicy.currentMatchesBound(boundUid, uid());
        boolean successful = !mismatch && current != null && current.getUid().equals(successfulUid);
        states.setValue(new AuthUiState(auth != null, mismatch, boundUid != null, current != null, current != null && current.isAnonymous(),
                connected.contains(AccountPolicy.EMAIL), connected.contains(AccountPolicy.PHONE),
                emailPresent, current != null && current.isEmailVerified(), emailPresent ? current.getEmail() : "",
                work.busy(), phoneFlow.active(), verificationId != null,
                phoneFlow.active() && resendToken != null && remaining == 0 && !work.busy(),
                phoneFlow.active() && (!work.busy() || workType == Work.PHONE_REQUEST),
                remaining, consent, phone, createEmail, mismatch ? R.string.auth_error_bound_existing : message, successful ? successSequence : 0,
                successful && autoFinish));
    }

    @Override protected void onCleared() {
        cleared = true;
        if (auth != null) auth.removeAuthStateListener(authListener);
        handler.removeCallbacksAndMessages(null);
        clearPhone();
        super.onCleared();
    }
}
