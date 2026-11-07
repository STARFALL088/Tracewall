package tracewall.child.sync;

import android.content.Context;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import tracewall.child.apps.InstalledAppCatalog;
import tracewall.data.AppInventory;
import tracewall.net.BoundedWorkPool;
import tracewall.net.LatestWork;

/** One active SDK operation plus only the latest desired snapshot/stop; uploads cannot outlive their consent. */
final class AppInventoryPublisher {
    private static final ExecutorService IO = BoundedWorkPool.create("tracewall-app-inventory", 1, 1);
    private static final LatestWork<Request> WORK = new LatestWork<>();
    private AppInventoryPublisher() {}
    private static final class Request {
        final Context app;
        final FirebaseFirestore store;
        final String uid;
        final FirebaseSync owner;
        final long ticket;
        final boolean sharing;
        final AtomicBoolean finished = new AtomicBoolean();
        Request() { app = null; store = null; uid = null; owner = null; ticket = -1; sharing = false; }
        Request(Context context, FirebaseFirestore store, String uid, FirebaseSync owner, long ticket, boolean sharing) {
            app = context.getApplicationContext(); this.store = store; this.uid = uid;
            this.owner = owner; this.ticket = ticket; this.sharing = sharing;
        }
        boolean current(Runnable work) { return owner != null && owner.inventoryWork(ticket, sharing, work); }
    }
    static void publish(Context context, FirebaseFirestore store, String deviceUid, FirebaseSync owner, long ticket, boolean sharing) {
        offer(new Request(context, store, deviceUid, owner, ticket, sharing));
    }
    /** Replaces pending work with a no-op, without pretending an SDK commit already submitted can be recalled. */
    static void stop() { offer(new Request()); }
    private static void offer(Request request) { Request next = WORK.offer(request); if (next != null) start(next); }
    private static void start(Request request) {
        if (request.owner == null) { finished(request, false); return; }
        IO.execute(() -> {
            try {
                if (!request.current(() -> {})) { finished(request, false); return; }
                Map<String, AppInventory.Entry> installed = new HashMap<>();
                long now = System.currentTimeMillis();
                if (request.sharing) for (InstalledAppCatalog.Entry entry :
                        InstalledAppCatalog.scan(request.app, AppInventory.MAX_ENTRIES)) {
                    try { installed.put(entry.packageName,
                            new AppInventory.Entry(entry.packageName, entry.label, entry.uid, now)); }
                    catch (IllegalArgumentException e) { Log.w("Tracewall", "Skipped invalid app-name metadata"); }
                }
                CollectionReference apps = request.store.collection("devices").document(request.uid).collection("apps");
                if (!request.current(() -> apps.limit(AppInventory.MAX_ENTRIES * 2L).get(Source.SERVER)
                    .addOnSuccessListener(previous -> {
                        if (!request.current(() -> {
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
                                        same = prior.uid == entry.uid && prior.label.equals(entry.label) && prior.packageName.equals(entry.packageName);
                                    } catch (IllegalArgumentException ignored) { /* Replace invalid old metadata. */ }
                                }
                                if (!same) updates.add(batch -> batch.set(apps.document(entry.packageName), entry.record()));
                            }
                            commit(request, updates, 0, previous.size() >= AppInventory.MAX_ENTRIES * 2);
                        })) finished(request, false);
                    }).addOnFailureListener(error -> {
                        Log.w("Tracewall", "App-name snapshot unavailable; refresh/reconnection may retry", error);
                        finished(request, false);
                    }))) finished(request, false);
            } catch (RuntimeException error) {
                Log.w("Tracewall", "Cannot collect app-name snapshot", error);
                finished(request, false);
            }
        });
    }
    private static void commit(Request request, List<Consumer<WriteBatch>> updates, int offset, boolean rescan) {
        if (!request.current(() -> {
            if (offset >= updates.size()) { finished(request, rescan); return; }
            WriteBatch batch = request.store.batch();
            int end = Math.min(offset + 400, updates.size());
            for (int i = offset; i < end; i++) updates.get(i).accept(batch);
            // Atomic consent/generation check and SDK issuance use the facade's SAME monitor as opt-out/deletion.
            batch.commit().addOnSuccessListener(done -> commit(request, updates, end, rescan))
                .addOnFailureListener(error -> {
                    Log.w("Tracewall", "App-name snapshot incomplete; refresh/reconnection may retry", error);
                    finished(request, false);
                });
        })) finished(request, false);
    }
    private static void finished(Request request, boolean rescan) {
        if (!request.finished.compareAndSet(false, true)) return;
        // LatestWork preserves a newer pending request over a repeat; never overwrite it with a rescan.
        Request next = WORK.finish(request, rescan);
        if (next != null) {
            if (next == request) request.finished.set(false);
            start(next);
        }
    }
}
