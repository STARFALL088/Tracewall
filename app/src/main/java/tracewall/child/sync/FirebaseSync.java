package tracewall.child.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseException;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.PersistentCacheSettings;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import tracewall.privacy.CloudDeletion;
import tracewall.privacy.PrivacyPolicy;

/**
 * Child-only Firebase facade. Every SDK write/listener callback is checked
 * against the current privacy consent and the immutable bound identity.
 *
 * <p>Cloud mode is an explicit, PIN-approved opt-in. This class never creates
 * an anonymous or replacement identity; the account screen chooses one and
 * {@link #onIdentityReady()} attaches the listener set after it is bound.
 */
public final class FirebaseSync {
    private static final String TAG = "FirebaseSync";
    private static final FirebaseSync INSTANCE = new FirebaseSync();
    private static final String[] UPLOAD_COLLECTIONS = {"history", "events", "apps"};
    private static final long DELETE_TIMEOUT_MS = 120_000;
    private final FirebaseAuth auth;
    private final FirebaseDatabase realtimeDb;
    private final FirebaseFirestore firestore;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> detach = new ArrayList<>();
    private final Map<ValueEventListener, DatabaseReference> rulesRefs = new HashMap<>();
    private final Set<String> revocationsInFlight = new HashSet<>();
    private volatile PrivacyPolicy policy = PrivacyPolicy.defaults();
    private volatile NetworkState networkState = NetworkState.STOPPING;
    private SharedPreferences preferences;
    private Context applicationContext;
    private RulesListener configuredRules;
    private String realtimeClaim, firestoreClaim;
    private Set<String> currentParents = Collections.emptySet();
    private boolean pairingWatchStarted;
    private DeleteAttempt deleting;

    public enum NetworkState { STOPPING, LOCAL, CONNECTING, IDENTITY_REQUIRED, CLOUD, ERROR, DELETING, DELETE_PENDING }
    public enum DeleteResult { CONFIRMED, PENDING, IDENTITY_UNAVAILABLE }
    public interface RulesListener { void onRulesUpdated(DataSnapshot snapshot); }

    private FirebaseSync() {
        auth = FirebaseAuth.getInstance();
        realtimeDb = FirebaseDatabase.getInstance();
        try { realtimeDb.setPersistenceEnabled(true); } catch (DatabaseException ignored) { /* Already configured. */ }
        realtimeDb.goOffline();
        firestore = FirebaseFirestore.getInstance();
        firestore.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build()).build());
        // First network operation before Tracewall references/listeners/writes; SDK shutdown is asynchronous, not recall.
        firestore.disableNetwork();
    }
    public static FirebaseSync get() { return INSTANCE; }

    public synchronized void initialize(Context context) {
        if (preferences != null) return;
        applicationContext = context.getApplicationContext();
        preferences = applicationContext.getSharedPreferences("tracewall.sync", Context.MODE_PRIVATE);
        // Absence of the NEW consent is local-only, including upgrades from an older auto-sync install.
        policy = new PrivacyPolicy(preferences.getBoolean("cloud_enabled", false),
            preferences.getBoolean("share_query_history", false), preferences.getBoolean("share_app_names", false),
            preferences.getBoolean("cloud_delete_pending", false));
        FirebaseUser current = auth.getCurrentUser();
        if (current != null) tracewall.auth.IdentityAnchor.bind(applicationContext, current.getUid());
    }

    /** Called after the account screen has authenticated and bound an identity. */
    public synchronized void onIdentityReady() {
        if (policy.allowsCloud()) reconfigure();
    }

    /** The current identity disappeared or no longer matches this protected device. */
    public synchronized void onIdentityLost() {
        stopListeners();
        if (policy.allowsCloud()) networkState = NetworkState.IDENTITY_REQUIRED;
    }
    /** Application supplies a process-lifetime rule handler; subscriptions themselves are mode-scoped. */
    public synchronized void start(RulesListener listener) {
        configuredRules = listener;
        reconfigure();
    }
    public PrivacyPolicy privacyPolicy() { return policy; }
    public NetworkState networkState() { return networkState; }
    public boolean isCloudEnabled() { return policy.allowsCloud(); }
    public boolean isHistorySharingEnabled() { return policy.history; }
    public boolean isInventorySharingEnabled() { return policy.inventory; }
    public boolean isCloudDeletionPending() { return policy.deletionPending; }
    public synchronized long lastConfirmedDeletionMs() {
        return preferences == null ? 0 : preferences.getLong("cloud_delete_verified_ms", 0);
    }
    public synchronized void retryDatabaseMode() { if (deleting == null) reconfigure(); }
    public long captureHistoryTicket() { return policy.historyTicket(); }

    // Callers must use PinGate.require before invoking ANY durable setting or destructive operation.
    public synchronized boolean setCloudEnabled(boolean enabled) {
        if (preferences == null || deleting != null || policy.deletionPending) return false;
        applyPolicy(policy.withCloud(enabled));
        return true;
    }
    public synchronized boolean setHistorySharingEnabled(boolean enabled) {
        if (preferences == null || deleting != null || policy.deletionPending) return false;
        applyPolicy(policy.withHistory(enabled));
        return true;
    }
    public synchronized boolean setInventorySharingEnabled(boolean enabled) {
        if (preferences == null || deleting != null || policy.deletionPending) return false;
        applyPolicy(policy.withInventory(enabled));
        return true;
    }
    private void persistPolicy() {
        preferences.edit().putBoolean("cloud_enabled", policy.cloud)
            .putBoolean("share_query_history", policy.history).putBoolean("share_app_names", policy.inventory)
            .putBoolean("cloud_delete_pending", policy.deletionPending).apply();
    }
    private void applyPolicy(PrivacyPolicy next) {
        if (next == policy) return;
        policy = next;
        persistPolicy();
        reconfigure();
    }
    private void stopListeners() {
        for (Runnable remove : detach) remove.run();
        detach.clear();
        for (Map.Entry<ValueEventListener, DatabaseReference> entry : rulesRefs.entrySet())
            entry.getValue().removeEventListener(entry.getKey());
        rulesRefs.clear();
        pairingWatchStarted = false;
        realtimeClaim = firestoreClaim = null;
        currentParents = Collections.emptySet();
        AppInventoryPublisher.stop();
    }
    private void reconfigure() {
        stopListeners();
        long ticket = policy.generation;
        if (!policy.allowsCloud()) { disableDatabases(ticket); return; }
        networkState = NetworkState.CONNECTING;
        firestore.enableNetwork().addOnSuccessListener(v -> cloudWork(ticket, () -> {
            realtimeDb.goOnline();
            if (uid() == null) { networkState = NetworkState.IDENTITY_REQUIRED; return; }
            watchPairing();
            refreshInstalledApps();
            if (configuredRules != null) listenForRules(configuredRules);
            networkState = NetworkState.CLOUD;
        })).addOnFailureListener(error -> cloudWork(ticket, () -> {
            networkState = NetworkState.ERROR;
            Log.w(TAG, "Cannot enable cloud networking", error);
        }));
    }
    private void disableDatabases(long ticket) {
        realtimeDb.goOffline();
        networkState = NetworkState.STOPPING;
        firestore.disableNetwork().addOnCompleteListener(done -> {
            synchronized (FirebaseSync.this) {
                if (policy.generation != ticket || policy.allowsCloud() || deleting != null) return;
                networkState = !done.isSuccessful() ? NetworkState.ERROR
                    : policy.deletionPending ? NetworkState.DELETE_PENDING : NetworkState.LOCAL;
            }
        });
    }
    /** Atomic check + SDK issuance: a mode change cannot slip between them. Never called from packet offers. */
    private synchronized boolean cloudWork(long ticket, Runnable action) {
        if (!policy.acceptsCloud(ticket)) return false;
        action.run();
        return true;
    }
    synchronized boolean inventoryWork(long ticket, boolean sharing, Runnable action) {
        if (!policy.acceptsInventory(ticket, sharing)) return false;
        action.run();
        return true;
    }
    public synchronized void refreshInstalledApps() {
        String deviceUid = uid();
        if (!policy.allowsCloud() || deviceUid == null || applicationContext == null) return;
        AppInventoryPublisher.publish(applicationContext, firestore, deviceUid, this, policy.generation, policy.inventory);
    }

    /** Background work must never create or replace an identity. */
    public void whenSignedIn(@NonNull Runnable callback) {
        if (uid() != null) callback.run();
    }
    public String uid() {
        FirebaseUser user = auth.getCurrentUser();
        return user != null && applicationContext != null
            && tracewall.auth.IdentityAnchor.matches(applicationContext, user.getUid()) ? user.getUid() : null;
    }

    public synchronized void watchPairing() {
        String deviceUid = uid();
        if (!policy.allowsCloud() || deviceUid == null || pairingWatchStarted) return;
        long ticket = policy.generation;
        pairingWatchStarted = true;
        DatabaseReference connected = realtimeDb.getReference(".info/connected");
        ValueEventListener connection = new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                cloudWork(ticket, () -> {
                    if (!Boolean.TRUE.equals(snapshot.getValue(Boolean.class))) return;
                    refreshInstalledApps();
                    for (String parent : new HashSet<>(preferences.getStringSet("pending_revocations", Collections.emptySet())))
                        revokeParent(parent, () -> {}, () -> {});
                });
            }
            public void onCancelled(@NonNull DatabaseError error) { Log.w(TAG, "Connectivity listener failed", error.toException()); }
        };
        connected.addValueEventListener(connection);
        detach.add(() -> connected.removeEventListener(connection));
        DatabaseReference device = realtimeDb.getReference("devices").child(deviceUid);
        DatabaseReference pairing = device.child("pairing");
        ValueEventListener pairingListener = new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                cloudWork(ticket, () -> {
                    realtimeClaim = snapshot.child("claimed_by").getValue(String.class);
                    clearUsedPairing(deviceUid, ticket);
                });
            }
            public void onCancelled(@NonNull DatabaseError error) { Log.w(TAG, "Pairing listener failed", error.toException()); }
        };
        pairing.addValueEventListener(pairingListener);
        detach.add(() -> pairing.removeEventListener(pairingListener));
        ListenerRegistration registration = firestore.collection("devices").document(deviceUid).collection("pairing")
            .document("current").addSnapshotListener((snapshot, error) -> cloudWork(ticket, () -> {
                if (error != null) { Log.w(TAG, "Pairing listener failed", error); return; }
                firestoreClaim = snapshot == null ? null : snapshot.getString("claimed_by");
                clearUsedPairing(deviceUid, ticket);
            }));
        detach.add(registration::remove);
        DatabaseReference parentsRef = device.child("parentUid");
        ChildEventListener removed = new ChildEventListener() {
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                cloudWork(ticket, () -> revokeParent(snapshot.getKey(), () -> {}, () -> {}));
            }
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previous) {}
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previous) {}
            public void onChildMoved(@NonNull DataSnapshot snapshot, String previous) {}
            public void onCancelled(@NonNull DatabaseError error) { Log.w(TAG, "Revocation listener failed", error.toException()); }
        };
        parentsRef.addChildEventListener(removed);
        detach.add(() -> parentsRef.removeEventListener(removed));
        ValueEventListener parentsListener = new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                cloudWork(ticket, () -> {
                    Set<String> parents = new HashSet<>();
                    for (DataSnapshot row : snapshot.getChildren()) parents.add(row.getKey());
                    currentParents = parents;
                    refreshInstalledApps();
                });
            }
            public void onCancelled(@NonNull DatabaseError error) { Log.w(TAG, "Parents listener failed", error.toException()); }
        };
        parentsRef.addValueEventListener(parentsListener);
        detach.add(() -> parentsRef.removeEventListener(parentsListener));
    }
    private void clearUsedPairing(String deviceUid, long ticket) {
        cloudWork(ticket, () -> {
            if (realtimeClaim == null || !realtimeClaim.equals(firestoreClaim)) return;
            realtimeDb.getReference("devices").child(deviceUid).child("pairing").removeValue();
            firestore.collection("devices").document(deviceUid).collection("pairing").document("current").delete();
        });
    }
    public synchronized void createPairing(Consumer<tracewall.security.PairingCode> ready, Runnable failed) {
        String deviceUid = uid();
        if (!policy.allowsCloud() || deviceUid == null) { failed.run(); return; }
        long ticket = policy.generation;
        tracewall.security.PairingCode pairing = new tracewall.security.PairingCode(deviceUid, tracewall.security.PairingCode.generate());
        Map<String, Object> value = new HashMap<>();
        value.put("code", pairing.code);
        value.put("expires_ms", System.currentTimeMillis() + tracewall.security.PairingCode.LIFETIME_MS);
        realtimeDb.getReference("devices").child(deviceUid).child("pairing").setValue(value)
            .addOnSuccessListener(v -> {
                if (!cloudWork(ticket, () -> firestore.collection("devices").document(deviceUid).collection("pairing")
                    .document("current").set(value).addOnSuccessListener(done -> {
                        if (!cloudWork(ticket, () -> ready.accept(pairing))) failed.run();
                    }).addOnFailureListener(error -> failed.run()))) failed.run();
            }).addOnFailureListener(error -> failed.run());
    }
    public synchronized void listParents(Consumer<List<String>> ready) {
        Set<String> parents = new TreeSet<>(currentParents);
        String deviceUid = uid();
        if (!policy.allowsCloud() || deviceUid == null) { ready.accept(new ArrayList<>(parents)); return; }
        long ticket = policy.generation;
        firestore.collection("devices").document(deviceUid).collection("parents").get(Source.SERVER)
            .addOnSuccessListener(rows -> cloudWork(ticket, () -> {
                for (DocumentSnapshot row : rows) parents.add(row.getId());
                ready.accept(new ArrayList<>(parents));
            })).addOnFailureListener(error -> ready.accept(new ArrayList<>(parents)));
    }
    public synchronized void revokeParent(String parentUid, Runnable confirmed, Runnable pending) {
        String deviceUid = uid();
        if (!policy.allowsCloud() || deviceUid == null || preferences == null) { pending.run(); return; }
        long ticket = policy.generation;
        updatePendingRevocation(parentUid, true);
        if (!revocationsInFlight.add(parentUid)) { pending.run(); return; }
        DatabaseReference realtime = realtimeDb.getReference("devices").child(deviceUid).child("parentUid").child(parentUid);
        DocumentReference member = firestore.collection("devices").document(deviceUid).collection("parents").document(parentUid);
        Task<Void> rt = realtime.removeValue(), fs = member.delete();
        Tasks.whenAllComplete(rt, fs).addOnCompleteListener(done -> {
            synchronized (FirebaseSync.this) {
                if (!policy.acceptsCloud(ticket) || !rt.isSuccessful() || !fs.isSuccessful()) {
                    finishRevocation(parentUid, false, confirmed, pending); return;
                }
                Task<DataSnapshot> readRt = realtime.get();
                Task<DocumentSnapshot> readFs = member.get(Source.SERVER);
                Tasks.whenAllComplete(readRt, readFs).addOnCompleteListener(read -> {
                    synchronized (FirebaseSync.this) {
                        finishRevocation(parentUid, policy.acceptsCloud(ticket) && readRt.isSuccessful() && readFs.isSuccessful()
                            && !readRt.getResult().exists() && !readFs.getResult().exists(), confirmed, pending);
                    }
                });
            }
        });
    }
    private void updatePendingRevocation(String parentUid, boolean add) {
        Set<String> next = new HashSet<>(preferences.getStringSet("pending_revocations", Collections.emptySet()));
        if (add) next.add(parentUid); else next.remove(parentUid);
        preferences.edit().putStringSet("pending_revocations", next).apply();
    }
    private void finishRevocation(String parentUid, boolean success, Runnable confirmed, Runnable pending) {
        if (success) updatePendingRevocation(parentUid, false);
        revocationsInFlight.remove(parentUid);
        if (success) confirmed.run(); else pending.run();
    }

    public synchronized ValueEventListener listenForRules(@NonNull RulesListener listener) {
        String deviceUid = uid();
        if (!policy.allowsCloud() || deviceUid == null) return null;
        long ticket = policy.generation;
        DatabaseReference ref = realtimeDb.getReference("devices").child(deviceUid).child("rules");
        ValueEventListener registration = new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) { cloudWork(ticket, () -> listener.onRulesUpdated(snapshot)); }
            public void onCancelled(@NonNull DatabaseError error) { Log.w(TAG, "Rules listener cancelled", error.toException()); }
        };
        ref.addValueEventListener(registration);
        rulesRefs.put(registration, ref);
        return registration;
    }
    public synchronized void removeRulesListener(@NonNull ValueEventListener listener) {
        DatabaseReference ref = rulesRefs.remove(listener);
        if (ref != null) ref.removeEventListener(listener);
    }

    /** Ticket is captured at packet offer, NOT at Room flush. Enabling consent never backfills queued/local rows. */
    public synchronized void logQuery(long ticket, long timeMs, String host, boolean blocked, int uid, String policyName) {
        String deviceUid = uid();
        if (!policy.acceptsHistory(ticket) || deviceUid == null) return;
        Map<String, Object> record = new HashMap<>();
        record.put("time_ms", timeMs); record.put("host", host); record.put("blocked", blocked);
        record.put("app_uid", uid); record.put("policy", policyName);
        firestore.collection("devices").document(deviceUid).collection("history").add(record)
            .addOnFailureListener(error -> Log.w(TAG, "History upload failed", error));
    }

    public synchronized void reportNewApp(String label, String packageName, long installedAt) {
        long ticket = policy.generation;
        if (!policy.acceptsInventory(ticket, true)) return;
        cloudWork(ticket, () -> inventoryWork(ticket, true, () -> {
            String deviceUid = uid();
            if (deviceUid == null) return;
            Map<String, Object> event = new HashMap<>();
            event.put("type", "new_app"); event.put("label", label); event.put("package", packageName); event.put("time_ms", installedAt);
            firestore.collection("devices").document(deviceUid).collection("events").add(event)
                .addOnFailureListener(error -> Log.w(TAG, "New app event failed", error));
        }));
    }
    public synchronized void reportProtectionOff(String reason) {
        long ticket = policy.generation, time = System.currentTimeMillis();
        if (!policy.allowsCloud()) return;
        cloudWork(ticket, () -> {
            String deviceUid = uid();
            if (deviceUid == null) return;
            Map<String, Object> event = new HashMap<>();
            event.put("type", "protection_off"); event.put("reason", reason); event.put("time_ms", time);
            firestore.collection("devices").document(deviceUid).collection("events").add(event)
                .addOnFailureListener(error -> Log.w(TAG, "Protection event failed", error));
        });
    }


    private static final class DeleteAttempt {
        final String uid;
        final CloudDeletion progress = new CloudDeletion();
        final Consumer<DeleteResult> result;
        Runnable timeout;
        DeleteAttempt(String uid, Consumer<DeleteResult> result) { this.uid = uid; this.result = result; }
    }
    /** Explicit PIN-approved exception to local networking: ONLY Firestore connects, solely to erase uploads.
     * Does not delete identity, memberships, pairing, parent-owned rules, exports or a parent's cached copies.
     */
    public synchronized void deleteCloudUploads(Consumer<DeleteResult> result) {
        String deviceUid = uid();
        if (deviceUid == null || preferences == null) { result.accept(DeleteResult.IDENTITY_UNAVAILABLE); return; }
        if (deleting != null) { result.accept(DeleteResult.PENDING); return; }
        policy = policy.beginDeletion();
        persistPolicy();
        preferences.edit().remove("cloud_delete_verified_ms").apply();
        stopListeners(); // Invalidated generations + serialized SDK issuance precede the pending-write barrier.
        realtimeDb.goOffline();
        DeleteAttempt attempt = new DeleteAttempt(deviceUid, result);
        deleting = attempt;
        networkState = NetworkState.DELETING;
        attempt.timeout = () -> finishDelete(attempt, false);
        main.postDelayed(attempt.timeout, DELETE_TIMEOUT_MS);
        firestore.enableNetwork().addOnSuccessListener(v -> {
            synchronized (FirebaseSync.this) {
                if (!active(attempt)) return;
                // Includes persistent writes from previous sessions. No new publisher can issue writes now.
                firestore.waitForPendingWrites().addOnSuccessListener(done -> deletePage(attempt, 0))
                    .addOnFailureListener(error -> finishDelete(attempt, false));
            }
        }).addOnFailureListener(error -> finishDelete(attempt, false));
    }
    private boolean active(DeleteAttempt attempt) {
        return deleting == attempt && policy.deletionPending && attempt.uid.equals(uid());
    }
    private synchronized void deletePage(DeleteAttempt attempt, int collection) {
        if (!active(attempt)) return;
        if (collection == UPLOAD_COLLECTIONS.length) { verifyDeleted(attempt, 0); return; }
        CollectionReference ref = firestore.collection("devices").document(attempt.uid).collection(UPLOAD_COLLECTIONS[collection]);
        // Repeatedly fetch the first remaining document-ID page after each acknowledged delete batch.
        // This is resumable pagination over a shrinking collection, not an unbounded collection get().
        ref.orderBy(FieldPath.documentId()).limit(CloudDeletion.PAGE_SIZE).get(Source.SERVER)
            .addOnSuccessListener(rows -> {
                synchronized (FirebaseSync.this) {
                    if (!active(attempt)) return;
                    switch (attempt.progress.page(rows.size(), rows.getMetadata().isFromCache(), rows.getMetadata().hasPendingWrites())) {
                        case NEXT_COLLECTION: deletePage(attempt, collection + 1); return;
                        case PENDING: finishDelete(attempt, false); return;
                        case DELETE:
                            WriteBatch batch = firestore.batch();
                            for (DocumentSnapshot row : rows) batch.delete(row.getReference());
                            batch.commit().addOnSuccessListener(done -> deletePage(attempt, collection))
                                .addOnFailureListener(error -> finishDelete(attempt, false));
                    }
                }
            }).addOnFailureListener(error -> finishDelete(attempt, false));
    }
    private synchronized void verifyDeleted(DeleteAttempt attempt, int collection) {
        if (!active(attempt)) return;
        if (collection == UPLOAD_COLLECTIONS.length) { finishDelete(attempt, attempt.progress.confirmed()); return; }
        firestore.collection("devices").document(attempt.uid).collection(UPLOAD_COLLECTIONS[collection]).limit(1).get(Source.SERVER)
            .addOnSuccessListener(rows -> {
                synchronized (FirebaseSync.this) {
                    if (!active(attempt)) return;
                    if (!attempt.progress.verify(collection, rows.size(), rows.getMetadata().isFromCache(), rows.getMetadata().hasPendingWrites())) {
                        finishDelete(attempt, false); return;
                    }
                    verifyDeleted(attempt, collection + 1);
                }
            }).addOnFailureListener(error -> finishDelete(attempt, false));
    }
    private synchronized void finishDelete(DeleteAttempt attempt, boolean verified) {
        if (deleting != attempt) return;
        // Invalidate the attempt BEFORE asynchronous shutdown; delayed page/commit callbacks cannot enqueue more work.
        deleting = null;
        main.removeCallbacks(attempt.timeout);
        networkState = NetworkState.STOPPING;
        long ticket = policy.generation;
        firestore.disableNetwork().addOnCompleteListener(disabled -> {
            synchronized (FirebaseSync.this) {
                if (policy.generation != ticket || deleting != null) { attempt.result.accept(DeleteResult.PENDING); return; }
                boolean success = verified && disabled.isSuccessful() && attempt.uid.equals(uid());
                if (success) {
                    policy = policy.finishDeletion();
                    persistPolicy();
                    preferences.edit().putLong("cloud_delete_verified_ms", System.currentTimeMillis()).apply();
                }
                networkState = success ? NetworkState.LOCAL : disabled.isSuccessful() ? NetworkState.DELETE_PENDING : NetworkState.ERROR;
                attempt.result.accept(success ? DeleteResult.CONFIRMED : DeleteResult.PENDING);
            }
        });
    }
}
