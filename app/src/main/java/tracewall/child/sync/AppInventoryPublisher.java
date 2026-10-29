package tracewall.child.sync;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.Log;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import tracewall.data.AppInventory;
import tracewall.net.BoundedWorkPool;
import tracewall.net.LatestWork;

/** Serializes snapshots and opt-out deletion so an older in-flight upload cannot win last. */
final class AppInventoryPublisher {
    private static final ExecutorService IO = BoundedWorkPool.create("tracewall-app-inventory", 1, 1);
    private static final LatestWork<Request> WORK = new LatestWork<>();
    private AppInventoryPublisher() {}

    private static final class Request {
        final Context app; final FirebaseFirestore store; final String uid; final BooleanSupplier allowed;
        Request(Context context, FirebaseFirestore store, String uid, BooleanSupplier allowed) {
            app = context.getApplicationContext(); this.store = store; this.uid = uid; this.allowed = allowed;
        }
    }
    static void publish(Context context, FirebaseFirestore store, String deviceUid, BooleanSupplier allowed) {
        Request next = WORK.offer(new Request(context, store, deviceUid, allowed));
        if (next != null) start(next);
    }
    private static void start(Request request) {
        IO.execute(() -> {
            try {
                boolean sharing = request.allowed.getAsBoolean();
                Context app = request.app;
                Map<String, AppInventory.Entry> installed = new HashMap<>();
                long now = System.currentTimeMillis();
                if (sharing) for (PackageInfo info : app.getPackageManager().getInstalledPackages(0)) {
                    ApplicationInfo ai = info.applicationInfo;
                    if (ai == null || ai.uid < 10000 || ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)) continue;
                    if (installed.size() == AppInventory.MAX_ENTRIES) {
                        Log.w("Tracewall", "App-name snapshot capped at " + AppInventory.MAX_ENTRIES);
                        break;
                    }
                    String label = String.valueOf(ai.loadLabel(app.getPackageManager()));
                    if (label.length() > 200) label = label.substring(0,
                        Character.isHighSurrogate(label.charAt(199)) ? 199 : 200);
                    try { installed.put(info.packageName, new AppInventory.Entry(info.packageName, label, ai.uid, now)); }
                    catch (IllegalArgumentException e) { Log.w("Tracewall", "Skipped invalid app-name metadata"); }
                }
                CollectionReference apps = request.store.collection("devices").document(request.uid).collection("apps");
                apps.limit(AppInventory.MAX_ENTRIES * 2L).get(Source.SERVER).addOnSuccessListener(previous -> {
                    if (request.allowed.getAsBoolean() != sharing) { finished(request, false); return; }
                    List<Consumer<WriteBatch>> updates = new ArrayList<>();
                    Map<String, DocumentSnapshot> existing = new HashMap<>();
                    for (DocumentSnapshot doc : previous.getDocuments()) {
                        existing.put(doc.getId(), doc);
                        if (!installed.containsKey(doc.getId())) updates.add(batch -> batch.delete(doc.getReference()));
                    }
                    for (AppInventory.Entry entry : installed.values()) {
                        DocumentSnapshot old = existing.get(entry.packageName);
                        boolean same = false;
                        if (old != null) {
                            try {
                                AppInventory.Entry prior = AppInventory.Entry.parse(old.getData());
                                same = prior.uid == entry.uid && prior.label.equals(entry.label)
                                    && prior.packageName.equals(entry.packageName);
                            } catch (IllegalArgumentException ignored) { /* Replace invalid old metadata. */ }
                        }
                        if (!same) updates.add(batch -> batch.set(apps.document(entry.packageName), entry.record()));
                    }
                    commit(request, updates, 0, sharing, previous.size() >= AppInventory.MAX_ENTRIES * 2);
                }).addOnFailureListener(error -> {
                    finished(request, false);
                    Log.w("Tracewall", "App-name snapshot unavailable; cloud rules/network may need attention", error);
                });
            } catch (RuntimeException error) {
                finished(request, false);
                Log.w("Tracewall", "Cannot collect app-name snapshot", error);
            }
        });
    }

    private static void commit(Request request, List<Consumer<WriteBatch>> updates,
            int offset, boolean sharing, boolean rescan) {
        if (request.allowed.getAsBoolean() != sharing) { finished(request, false); return; }
        if (offset >= updates.size()) { finished(request, rescan); return; }
        WriteBatch batch = request.store.batch();
        int end = Math.min(offset + 400, updates.size());
        for (int i = offset; i < end; i++) updates.get(i).accept(batch);
        batch.commit().addOnSuccessListener(done -> commit(request, updates, end, sharing, rescan))
            .addOnFailureListener(error -> {
                finished(request, false);
                Log.w("Tracewall", "App-name snapshot incomplete; a later refresh will retry", error);
            });
    }
    private static void finished(Request request, boolean rescan) {
        Request next = WORK.finish(request, rescan);
        if (next != null) start(next);
    }
}
