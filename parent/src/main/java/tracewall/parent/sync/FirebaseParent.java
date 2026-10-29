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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            .addOnFailureListener(e -> Log.e(TAG, "sign-in failed", e));
    }

    /** @return current parent UID, or {@code null} if not yet signed in. */
    public String uid() {
        FirebaseUser u = auth.getCurrentUser();
        return u == null ? null : u.getUid();
    }

    // -------------------------------------------------------------- pairing

    /** Atomically claim the code in each database. Neither registration can be overwritten. */
    public void registerAsParent(String childUid, String code, Runnable onSuccess, Runnable onFail) {
        String parentUid = uid();
        if (parentUid == null) { onFail.run(); return; }
        Map<String, Object> updates = new HashMap<>();
        updates.put("parentUid/" + parentUid, code);
        updates.put("pairing/claimed_by", parentUid);
        db.getReference("devices").child(childUid).updateChildren(updates)
            .addOnSuccessListener(v -> {
                com.google.firebase.firestore.DocumentReference device = fs.collection("devices").document(childUid);
                com.google.firebase.firestore.WriteBatch batch = fs.batch();
                Map<String, Object> membership = new HashMap<>();
                membership.put("code", code);
                batch.set(device.collection("parents").document(parentUid), membership);
                batch.update(device.collection("pairing").document("current"), "claimed_by", parentUid);
                batch.commit().addOnSuccessListener(done -> onSuccess.run())
                    .addOnFailureListener(e -> { Log.e(TAG, "Firestore pairing failed", e); onFail.run(); });
            })
            .addOnFailureListener(e -> { Log.e(TAG, "RTDB pairing failed", e); onFail.run(); });
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

    /**
     * Push allow-list and blocked-UID rules to the child device.
     * Uses {@code updateChildren} so that a concurrent {@link #pushCategories}
     * call on the same node is not overwritten.
     */
    public void pushRules(
        @NonNull String childUid,
        @NonNull List<String> allow,
        @NonNull List<Integer> blockedUids
    ) {
        Map<String, Object> allowMap = new HashMap<>();
        for (int i = 0; i < allow.size(); i++) allowMap.put(
            String.valueOf(i),
            allow.get(i)
        );

        Map<String, Object> uidsMap = new HashMap<>();
        for (int i = 0; i < blockedUids.size(); i++) uidsMap.put(
            String.valueOf(i),
            blockedUids.get(i).longValue()
        );

        Map<String, Object> updates = new HashMap<>();
        updates.put("allow", allowMap);
        updates.put("blocked_uids", uidsMap);

        db.getReference("devices")
            .child(childUid)
            .child("rules")
            .updateChildren(updates)
            .addOnSuccessListener(v ->
                Log.i(TAG, "rules pushed to " + childUid)
            )
            .addOnFailureListener(e -> Log.e(TAG, "rule push failed", e));
    }

    /**
     * Push category toggles to the child device.
     * The child's {@code BlocklistStore} reloads with only the enabled
     * categories when the snapshot arrives.
     *
     * @param childUid   the paired child's UID
     * @param categories map of category name → enabled; e.g. {"ads": false}
     */
    public void pushCategories(
        @NonNull String childUid,
        @NonNull Map<String, Boolean> categories
    ) {
        db.getReference("devices")
            .child(childUid)
            .child("rules")
            .child("categories")
            .setValue(categories)
            .addOnSuccessListener(v ->
                Log.i(TAG, "categories pushed to " + childUid)
            )
            .addOnFailureListener(e -> Log.e(TAG, "category push failed", e));
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
