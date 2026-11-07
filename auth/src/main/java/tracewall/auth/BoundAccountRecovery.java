package tracewall.auth;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Check recovery candidates without exposing the primary SDK's persisted queues to another UID. */
final class BoundAccountRecovery {
    static final String APP_NAME = "tracewall-bound-recovery";
    private static boolean busy;

    enum Reason { WRONG_IDENTITY, STALE, BUSY }

    static final class Failure extends Exception {
        final Reason reason;
        Failure(Reason reason) { super(reason.name()); this.reason = reason; }
    }

    private BoundAccountRecovery() { }

    static Task<AuthResult> signIn(FirebaseAuth primary, AuthCredential credential, String boundUid,
                                  BooleanSupplier owned, Executor executor) {
        return signIn(primary, credential, boundUid, owned, executor, FirebaseAuth::getInstance);
    }

    /** The factory is injectable only for tests that use the real, local Firebase Auth emulator. */
    static Task<AuthResult> signIn(FirebaseAuth primary, AuthCredential credential, String boundUid,
                                  BooleanSupplier owned, Executor executor,
                                  Function<FirebaseApp, FirebaseAuth> secondaryFactory) {
        if (boundUid == null || boundUid.isEmpty() || primary.getCurrentUser() != null || !owned.getAsBoolean())
            return Tasks.forException(new Failure(Reason.STALE));
        synchronized (BoundAccountRecovery.class) {
            if (busy) return Tasks.forException(new Failure(Reason.BUSY));
            busy = true;
        }
        Operation operation = new Operation(primary, boundUid, owned, executor);
        try {
            // A fixed name bounds private SDK storage after process death. Clear any restored
            // secondary session before use; no database client is ever created for this app.
            try {
                operation.app = FirebaseApp.getInstance(APP_NAME);
            } catch (IllegalStateException absent) {
                operation.app = FirebaseApp.initializeApp(primary.getApp().getApplicationContext(),
                    primary.getApp().getOptions(), APP_NAME);
            }
            operation.secondary = secondaryFactory.apply(operation.app);
            operation.secondary.signOut();
            if (primary.getTenantId() != null) operation.secondary.setTenantId(primary.getTenantId());
            operation.secondary.useAppLanguage();
            operation.secondary.signInWithCredential(credential).addOnCompleteListener(executor, operation::verified);
        } catch (Exception failure) {
            operation.finish(null, failure);
        }
        return operation.completion.getTask();
    }

    private static final class Operation {
        final FirebaseAuth primary;
        final String boundUid;
        final BooleanSupplier owned;
        final Executor executor;
        final TaskCompletionSource<AuthResult> completion = new TaskCompletionSource<>();
        FirebaseApp app;
        FirebaseAuth secondary;
        boolean finished;

        Operation(FirebaseAuth primary, String boundUid, BooleanSupplier owned, Executor executor) {
            this.primary = primary;
            this.boundUid = boundUid;
            this.owned = owned;
            this.executor = executor;
        }

        void verified(Task<AuthResult> result) {
            try {
                if (!result.isSuccessful()) { finish(null, result.getException()); return; }
                FirebaseUser candidate = result.getResult() == null ? null : result.getResult().getUser();
                if (!owned.getAsBoolean() || primary.getCurrentUser() != null) {
                    finish(null, new Failure(Reason.STALE));
                    return;
                }
                if (!AccountPolicy.canPromoteRecovery(boundUid, candidate == null ? null : candidate.getUid(), null)) {
                    finish(null, new Failure(Reason.WRONG_IDENTITY));
                    return;
                }
                // Copy the verified SAME-PROJECT user, rather than consuming the credential twice.
                // Only the exact anchored UID can ever reach the primary auth instance.
                primary.updateCurrentUser(candidate).addOnCompleteListener(executor, promoted -> {
                    if (!promoted.isSuccessful()) { finish(null, promoted.getException()); return; }
                    FirebaseUser current = primary.getCurrentUser();
                    if (current == null || !boundUid.equals(current.getUid())) {
                        finish(null, new Failure(Reason.STALE));
                        return;
                    }
                    finish(result.getResult(), null);
                });
            } catch (Exception failure) {
                finish(null, failure);
            }
        }

        synchronized void finish(AuthResult result, Exception failure) {
            if (finished) return;
            finished = true;
            try {
                // This is an isolated verification session, NEVER the primary account.
                if (secondary != null) secondary.signOut();
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
            } finally {
                try {
                    if (app != null) app.delete();
                } catch (RuntimeException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                } finally {
                    synchronized (BoundAccountRecovery.class) { busy = false; }
                }
            }
            if (failure != null) completion.setException(failure);
            else if (result != null) completion.setResult(result);
            else completion.setException(new Failure(Reason.STALE));
        }
    }
}
