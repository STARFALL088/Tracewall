package tracewall.parent.sync;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.PersistentCacheSettings;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.Source;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tracewall.security.PairingPlan;
import tracewall.data.RuleEdits;

/**
 * Parent-side Firebase facade.
 *
 * <p>Pairing requires the child's UID and a short-lived one-time code.
 * Each database independently validates an atomic claim and parent registration.
 *
 * <p>Call {@link #signInAnonymously} once from
 * {@code TracewallParentApp.onCreate()} before any Activity starts.
 */
public class FirebaseParent {

    private static final String TAG = "TracewallParent";

    private static final FirebaseParent INSTANCE = new FirebaseParent();

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseDatabase db = FirebaseDatabase.getInstance();
    private final FirebaseFirestore fs = FirebaseFirestore.getInstance();

    public interface HistoryListener {
        void onHistory(List<Map<String, Object>> records);
    }

    private FirebaseParent() {
        try {
            db.setPersistenceEnabled(true);
        } catch (DatabaseException ignored) {}

        fs.setFirestoreSettings(
            new FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder().build()
                )
                .build()
        );
    }

    public static FirebaseParent get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- auth

    public void signInAnonymously(@NonNull Runnable callback) {
        signInAnonymously(callback, () -> {});
    }

    public void signInAnonymously(@NonNull Runnable callback, @NonNull Runnable failed) {
        FirebaseUser current = auth.getCurrentUser();
        if (current != null) {
            callback.run();
            return;
        }
        auth.signInAnonymously()
            .addOnSuccessListener(r -> {
                Log.d(TAG, "signed in as " + r.getUser().getUid());
                callback.run();
            })
            .addOnFailureListener(e -> { Log.e(TAG, "sign-in failed", e); failed.run(); });
    }

    /** @return current parent UID, or {@code null} if not yet signed in. */
    public String uid() {
        FirebaseUser u = auth.getCurrentUser();
        return u == null ? null : u.getUid();
    }

    // -------------------------------------------------------------- pairing

    public enum PairingFailure { CONNECTION, CHILD_RESET_REQUIRED }

    /** Resume only the missing claim; existing membership is never overwritten. */
    public void registerAsParent(String childUid, String code, Runnable onSuccess,
            java.util.function.Consumer<PairingFailure> onFail) {
        signInAnonymously(() -> resumePairing(childUid, code, onSuccess, onFail),
            () -> onFail.accept(PairingFailure.CONNECTION));
    }

    private void resumePairing(String childUid, String code, Runnable onSuccess,
            java.util.function.Consumer<PairingFailure> onFail) {
        String parentUid = uid();
        if (parentUid == null) { onFail.accept(PairingFailure.CONNECTION); return; }
        com.google.firebase.database.DatabaseReference realtime = db.getReference("devices").child(childUid);
        com.google.firebase.firestore.DocumentReference device = fs.collection("devices").document(childUid);
        realtime.child("parentUid").child(parentUid).get().addOnSuccessListener(rt ->
            device.collection("parents").document(parentUid).get(Source.SERVER).addOnSuccessListener(stored -> {
                String rtCode = rt.getValue(String.class);
                String fsCode = stored.exists() ? stored.getString("code") : null;
                PairingPlan.Action action = PairingPlan.next(rtCode, fsCode, code);
                if (action == PairingPlan.Action.CHILD_RESET_REQUIRED) {
                    onFail.accept(PairingFailure.CHILD_RESET_REQUIRED);
                    return;
                }
                if (action == PairingPlan.Action.COMPLETE) { onSuccess.run(); return; }
                Runnable finish = () -> {
                    if (action == PairingPlan.Action.REGISTER_REALTIME) { onSuccess.run(); return; }
                    com.google.firebase.firestore.WriteBatch batch = fs.batch();
                    Map<String, Object> membership = new HashMap<>();
                    membership.put("code", code);
                    batch.set(device.collection("parents").document(parentUid), membership);
                    batch.update(device.collection("pairing").document("current"), "claimed_by", parentUid);
                    batch.commit().addOnSuccessListener(done -> onSuccess.run())
                        .addOnFailureListener(e -> { Log.e(TAG, "Firestore pairing incomplete; retry same code", e);
                            onFail.accept(PairingFailure.CONNECTION); });
                };
                if (action == PairingPlan.Action.REGISTER_FIRESTORE) { finish.run(); return; }
                claimRealtime(realtime, parentUid, code, finish, () -> onFail.accept(PairingFailure.CONNECTION));
            }).addOnFailureListener(e -> { Log.e(TAG, "Cannot verify Firestore membership", e);
                onFail.accept(PairingFailure.CONNECTION); }))
            .addOnFailureListener(e -> { Log.e(TAG, "Cannot verify RTDB membership", e);
                onFail.accept(PairingFailure.CONNECTION); });
    }

    private void claimRealtime(com.google.firebase.database.DatabaseReference device, String parentUid,
            String code, Runnable success, Runnable failed) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("parentUid/" + parentUid, code);
        updates.put("pairing/claimed_by", parentUid);
        device.updateChildren(updates).addOnSuccessListener(v -> success.run())
            .addOnFailureListener(e -> { Log.e(TAG, "RTDB pairing failed", e); failed.run(); });
    }

    // ---------------------------------------------------------------- rules

    public com.google.firebase.database.ValueEventListener listenRules(
        String childUid, java.util.function.Consumer<com.google.firebase.database.DataSnapshot> listener,
        Runnable onError
    ) {
        com.google.firebase.database.ValueEventListener registration =
            new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snapshot) {
                    listener.accept(snapshot);
                }
                @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError error) {
                    Log.e(TAG, "rules listen failed: " + error.getMessage());
                    onError.run();
                }
            };
        db.getReference("devices").child(childUid).child("rules").addValueEventListener(registration);
        return registration;
    }

    public void removeRulesListener(String childUid, com.google.firebase.database.ValueEventListener listener) {
        db.getReference("devices").child(childUid).child("rules").removeEventListener(listener);
    }

    public interface WriteListener { void complete(boolean serverAccepted); }

    public void editAllow(String childUid, String domain, boolean add, WriteListener listener) {
        String normalized = RuleEdits.domain(domain);
        edit(childUid, "allow", current -> RuleEdits.allow(current, normalized, add), listener);
    }

    public void editBlockedUid(String childUid, int uid, boolean block, WriteListener listener) {
        edit(childUid, "blocked_uids", current -> RuleEdits.blocked(current, uid, block), listener);
    }

    public void editCategory(String childUid, String category, boolean enabled, WriteListener listener) {
        edit(childUid, "categories", current -> RuleEdits.category(current, category, enabled), listener);
    }

    /** The SDK retries this single intent against current server data on conflicts. */
    private void edit(String childUid, String field,
            java.util.function.Function<Object, Map<String, Object>> change, WriteListener listener) {
        db.getReference("devices").child(childUid).child("rules").child(field)
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @NonNull @Override public com.google.firebase.database.Transaction.Result doTransaction(
                        @NonNull com.google.firebase.database.MutableData data) {
                    try {
                        Map<String, Object> next = change.apply(data.getValue());
                        data.setValue(next.isEmpty() ? null : next);
                        return com.google.firebase.database.Transaction.success(data);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Rejected invalid rule edit", e);
                        return com.google.firebase.database.Transaction.abort();
                    }
                }
                @Override public void onComplete(com.google.firebase.database.DatabaseError error,
                        boolean committed, com.google.firebase.database.DataSnapshot snapshot) {
                    if (error != null) Log.w(TAG, "Rule edit was not accepted", error.toException());
                    listener.complete(error == null && committed);
                }
            }, false);
    }

    /** Recent device events, including stable IDs for the tamper banner and activity feed. */
    public ListenerRegistration listenEvents(String childUid, HistoryListener listener) {
        return fs.collection("devices").document(childUid).collection("events")
            .orderBy("time_ms", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener((snap, err) -> {
                if (err != null) { Log.e(TAG, "events listen error", err); return; }
                if (snap == null) return;
                List<Map<String, Object>> events = new java.util.ArrayList<>();
                for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                    Map<String, Object> event = doc.getData();
                    if (event != null) {
                        event.put("id", doc.getId());
                        events.add(event);
                    }
                }
                listener.onHistory(events);
            });
    }

    // -------------------------------------------------------------- history

    /**
     * Subscribe to the child's Firestore history, most recent first, up to
     * {@code limit} records. The listener fires immediately with current data
     * and again on every subsequent write by the child.
     *
     * @param childUid the paired child's UID
     * @param limit    maximum number of records to return
     * @param listener called on the main thread with the result list
     * @return a registration handle — call {@link ListenerRegistration#remove()}
     *         when the caller is destroyed to avoid memory leaks.
     */
    public ListenerRegistration listenHistory(
        @NonNull String childUid,
        int limit,
        @NonNull HistoryListener listener
    ) {
        return fs
            .collection("devices")
            .document(childUid)
            .collection("history")
            .orderBy("time_ms", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener((snap, err) -> {
                if (err != null) {
                    Log.e(TAG, "history listen error", err);
                    return;
                }
                if (snap == null) return;
                java.util.List<Map<String, Object>> out =
                    new java.util.ArrayList<>();
                for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                    Map<String, Object> data = doc.getData();
                    if (data != null) out.add(data);
                }
                listener.onHistory(out);
            });
    }
}
