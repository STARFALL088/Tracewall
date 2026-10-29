package tracewall.child.sync;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;
import java.util.HashMap;
import java.util.Map;

/**
 * Central access point for all three Firebase services used by Tracewall.
 *
 * <p><b>Authentication</b> ({@link FirebaseAuth}): anonymous sign-in pairs the
 * child device with the parent without requiring the child to create an account.
 * The UID produced by anonymous auth identifies the device; pairing also requires
 * a one-time code. Both apps use it to
 * address the same Realtime Database node and Firestore documents.
 *
 * <p><b>Realtime Database</b> ({@link FirebaseDatabase}): low-latency
 * channel for the parent to push rule changes to the child in real time.
 * The child listens on {@code /devices/{uid}/rules} and reloads the policy
 * whenever a write arrives.
 *
 * <p><b>Cloud Firestore</b> ({@link FirebaseFirestore}): structured, queryable
 * storage for DNS history that the parent app can page through without
 * loading all rows. History rows are written to
 * {@code /devices/{uid}/history/{recordId}}.
 *
 * <p>Call {@link #signInAnonymously} once at app startup (e.g. from
 * {@code Application.onCreate}). All other methods are no-ops until the sign-in
 * callback fires.
 *
 * <p>Replace {@code app/google-services.json} with the real file from your
 * Firebase console before using any of these APIs at runtime.
 */
public class FirebaseSync {

    private static final String TAG = "FirebaseSync";

    private static final FirebaseSync INSTANCE = new FirebaseSync();

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseDatabase realtimeDb = FirebaseDatabase.getInstance();
    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();

    /** Listener notified when the parent pushes a new rule set. */
    public interface RulesListener {
        /** @param snapshot raw JSON value at {@code /devices/{uid}/rules} */
        void onRulesUpdated(DataSnapshot snapshot);
    }

    private FirebaseSync() {
        // Enable disk persistence for the Realtime Database before any
        // DatabaseReference is obtained. Throws DatabaseException if called
        // more than once in a process (e.g. during instrumented tests), so
        // we swallow the duplicate-call exception only.
        try {
            realtimeDb.setPersistenceEnabled(true);
        } catch (DatabaseException ignored) {}

        // Persist Firestore reads and pending writes across process restarts
        // so the child's history and rule state survive offline periods.
        FirebaseFirestoreSettings firestoreSettings =
            new FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder().build()
                )
                .build();
        firestore.setFirestoreSettings(firestoreSettings);
    }

    public static FirebaseSync get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- auth

    /**
     * Sign in anonymously so that both the child and parent apps share a
     * stable UID without requiring user credentials on the child device.
     *
     * @param callback called on the main thread once the UID is available
     *                 (or immediately if the user is already signed in).
     */
    public void signInAnonymously(@NonNull Runnable callback) {
        FirebaseUser current = auth.getCurrentUser();
        if (current != null) {
            Log.d(TAG, "Already signed in as " + current.getUid());
            callback.run();
            return;
        }

        auth.signInAnonymously()
            .addOnSuccessListener(result -> {
                Log.d(TAG, "Signed in as " + result.getUser().getUid());
                callback.run();
            })
            .addOnFailureListener(e ->
                Log.e(TAG, "Anonymous sign-in failed", e)
            );
    }

    /** @return the current Firebase UID, or {@code null} if not yet signed in. */
    public String uid() {
        FirebaseUser u = auth.getCurrentUser();
        return u == null ? null : u.getUid();
    }

    private String realtimeClaim;
    private String firestoreClaim;
    private java.util.Set<String> currentParents = java.util.Collections.emptySet();
    private boolean pairingWatchStarted;

    /** Start child-side cleanup after BOTH databases have accepted the same parent. */
    public void watchPairing() {
        String deviceUid = uid();
        if (deviceUid == null || pairingWatchStarted) return;
        pairingWatchStarted = true;
        DatabaseReference device = realtimeDb.getReference("devices").child(deviceUid);
        device.child("pairing").addValueEventListener(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                realtimeClaim = snapshot.child("claimed_by").getValue(String.class);
                clearUsedPairing(deviceUid);
            }
            public void onCancelled(@NonNull DatabaseError error) { Log.e(TAG, "pairing watch failed", error.toException()); }
        });
        firestore.collection("devices").document(deviceUid).collection("pairing")
            .document("current").addSnapshotListener((snapshot, error) -> {
                if (error != null) { Log.e(TAG, "pairing watch failed", error); return; }
                firestoreClaim = snapshot == null ? null : snapshot.getString("claimed_by");
                clearUsedPairing(deviceUid);
            });
        device.child("parentUid").addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                firestore.collection("devices").document(deviceUid).collection("parents")
                    .document(snapshot.getKey()).delete();
            }
            public void onChildAdded(@NonNull DataSnapshot s, String previous) {}
            public void onChildChanged(@NonNull DataSnapshot s, String previous) {}
            public void onChildMoved(@NonNull DataSnapshot s, String previous) {}
            public void onCancelled(@NonNull DatabaseError error) { Log.e(TAG, "revocation watch failed", error.toException()); }
        });
        device.child("parentUid").addValueEventListener(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                java.util.Set<String> parents = new java.util.HashSet<>();
                for (DataSnapshot row : snapshot.getChildren()) parents.add(row.getKey());
                currentParents = parents;

            }
            public void onCancelled(@NonNull DatabaseError error) { Log.e(TAG, "parents watch failed", error.toException()); }
        });
    }

    private void clearUsedPairing(String deviceUid) {
        if (realtimeClaim == null || !realtimeClaim.equals(firestoreClaim)) return;
        realtimeDb.getReference("devices").child(deviceUid).child("pairing").removeValue();
        firestore.collection("devices").document(deviceUid).collection("pairing").document("current").delete();
    }

    public void createPairing(java.util.function.Consumer<tracewall.security.PairingCode> ready, Runnable failed) {
        String deviceUid = uid();
        if (deviceUid == null) { failed.run(); return; }
        tracewall.security.PairingCode pairing = new tracewall.security.PairingCode(
            deviceUid, tracewall.security.PairingCode.generate());
        Map<String, Object> value = new HashMap<>();
        value.put("code", pairing.code);
        value.put("expires_ms", System.currentTimeMillis() + tracewall.security.PairingCode.LIFETIME_MS);
        realtimeDb.getReference("devices").child(deviceUid).child("pairing").setValue(value)
            .addOnSuccessListener(v -> firestore.collection("devices").document(deviceUid)
                .collection("pairing").document("current").set(value)
                .addOnSuccessListener(done -> ready.accept(pairing))
                .addOnFailureListener(e -> failed.run()))
            .addOnFailureListener(e -> failed.run());
    }

    public void listParents(java.util.function.Consumer<java.util.List<String>> ready) {
        java.util.Set<String> parents = new java.util.TreeSet<>(currentParents);
        String deviceUid = uid();
        if (deviceUid == null) { ready.accept(new java.util.ArrayList<>(parents)); return; }
        firestore.collection("devices").document(deviceUid).collection("parents").get()
            .addOnSuccessListener(rows -> {
                for (com.google.firebase.firestore.DocumentSnapshot row : rows) parents.add(row.getId());
                ready.accept(new java.util.ArrayList<>(parents));
            }).addOnFailureListener(e -> ready.accept(new java.util.ArrayList<>(parents)));
    }

    public void revokeParent(String parentUid) {
        String deviceUid = uid();
        if (deviceUid == null) return;
        realtimeDb.getReference("devices").child(deviceUid).child("parentUid").child(parentUid).removeValue();
        firestore.collection("devices").document(deviceUid).collection("parents").document(parentUid).delete();
    }

    // ----------------------------------------------------------- realtime db

    /**
     * Subscribe to rule changes pushed by the parent.
     * The listener is called immediately with the current value, then again
     * whenever the parent modifies {@code /devices/{uid}/rules}.
     *
     * <p>Must be called after {@link #signInAnonymously} has completed.
     *
     * @return the attached {@link ValueEventListener} - pass it to
     *         {@link #removeRulesListener} when the caller is destroyed.
     */
    public ValueEventListener listenForRules(@NonNull RulesListener listener) {
        String uid = requireUid("listenForRules");
        if (uid == null) return null;

        DatabaseReference ref = realtimeDb
            .getReference("devices")
            .child(uid)
            .child("rules");
        ValueEventListener vel = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                listener.onRulesUpdated(snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Rules listener cancelled: " + error.getMessage());
            }
        };
        ref.addValueEventListener(vel);
        return vel;
    }

    /** Detach a previously attached rules listener to avoid memory leaks. */
    public void removeRulesListener(@NonNull ValueEventListener vel) {
        String uid = uid();
        if (uid == null) return;
        realtimeDb
            .getReference("devices")
            .child(uid)
            .child("rules")
            .removeEventListener(vel);
    }

    // ----------------------------------------------------------- firestore

    /**
     * Write a single DNS history record to Firestore so the parent app can
     * page through the child's history without loading the full Room database.
     *
     * <p>Must be called after {@link #signInAnonymously} has completed.
     *
     * @param timeMs    Unix epoch time of the query (milliseconds)
     * @param host      the queried hostname
     * @param blocked   whether the query was blocked
     * @param uid       Android UID of the app that made the query
     * @param policy    policy reason string (e.g. "category:adult")
     */
    public void logQuery(
        long timeMs,
        String host,
        boolean blocked,
        int uid,
        String policy
    ) {
        String firebaseUid = requireUid("logQuery");
        if (firebaseUid == null) return;

        Map<String, Object> record = new HashMap<>();
        record.put("time_ms", timeMs);
        record.put("host", host);
        record.put("blocked", blocked);
        record.put("app_uid", uid);
        record.put("policy", policy);

        CollectionReference history = firestore
            .collection("devices")
            .document(firebaseUid)
            .collection("history");

        history
            .add(record)
            .addOnFailureListener(e -> Log.e(TAG, "Firestore write failed", e));
    }

    /** Firestore's persistent queue retains this event while offline. */
    public void reportProtectionOff(String reason) {
        long time = System.currentTimeMillis();
        signInAnonymously(() -> {
            String deviceUid = uid();
            if (deviceUid == null) return;
            Map<String, Object> event = new HashMap<>();
            event.put("type", "protection_off");
            event.put("reason", reason);
            event.put("time_ms", time);
            firestore.collection("devices").document(deviceUid).collection("events")
                .add(event).addOnFailureListener(e -> Log.e(TAG, "event write failed", e));
        });
    }

    /**
     * Expose the Firestore history collection reference directly for callers
     * that want to run queries (e.g. paged reads by the parent app).
     *
     * @return collection reference, or {@code null} if not signed in.
     */
    public CollectionReference historyCollection() {
        String uid = requireUid("historyCollection");
        if (uid == null) return null;
        return firestore
            .collection("devices")
            .document(uid)
            .collection("history");
    }

    // ---------------------------------------------------------------- helpers

    private String requireUid(String caller) {
        String uid = uid();
        if (uid == null) {
            Log.w(
                TAG,
                caller +
                    " called before sign-in; call signInAnonymously() first"
            );
        }
        return uid;
    }
}
