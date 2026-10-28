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
 * <p><b>Pairing model</b>: the child device displays its Firebase UID as a
 * "device code". The parent enters that code here. On pairing,
 * {@link #registerAsParent} writes {@code /devices/{childUid}/parentUid/{parentUid} = true}
 * to the Realtime Database. The security rules then allow the parent UID to
 * read and write the child's data for the lifetime of the pairing.
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

    /**
     * Register this device as the parent of the child identified by
     * {@code childUid}. Writes {@code /devices/{childUid}/parentUid/{parentUid} = true}
     * so the RTDB security rules will grant this parent full access.
     *
     * @param childUid  the UID shown on the child's device
     * @param onSuccess called on the main thread when the write succeeds
     * @param onFail    called on the main thread when the write fails
     */
    public void registerAsParent(
        @NonNull String childUid,
        @NonNull Runnable onSuccess,
        @NonNull Runnable onFail
    ) {
        String parentUid = uid();
        if (parentUid == null) {
            Log.w(TAG, "registerAsParent: not signed in");
            onFail.run();
            return;
        }
        // Write to RTDB (for real-time rules channel security)
        db.getReference("devices")
            .child(childUid)
            .child("parentUid")
            .child(parentUid)
            .setValue(true)
            .addOnSuccessListener(v -> {
                Log.i(TAG, "paired with child " + childUid + " (RTDB)");
                // Mirror to Firestore so the Firestore security rules can
                // also verify this pairing without cross-database lookups.
                fs.collection("devices")
                    .document(childUid)
                    .collection("parents")
                    .document(parentUid)
                    .set(new HashMap<>())
                    .addOnSuccessListener(fv -> {
                        Log.i(
                            TAG,
                            "paired with child " + childUid + " (Firestore)"
                        );
                        onSuccess.run();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Firestore pairing write failed", e);
                        // RTDB succeeded so the child can still receive rules;
                        // Firestore history reads will fail until this is fixed.
                        onFail.run();
                    });
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "pairing failed", e);
                onFail.run();
            });
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

    /** Latest protection-off event, including its stable document ID for dismissal. */
    public ListenerRegistration listenEvents(String childUid, HistoryListener listener) {
        return fs.collection("devices").document(childUid).collection("events")
            .orderBy("time_ms", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener((snap, err) -> {
                if (err != null) { Log.e(TAG, "events listen error", err); return; }
                if (snap == null) return;
                List<Map<String, Object>> events = new java.util.ArrayList<>();
                for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                    Map<String, Object> event = doc.getData();
                    if (event != null && "protection_off".equals(event.get("type"))) {
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
