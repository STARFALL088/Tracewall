package tracewall.child.io;

import android.app.Activity;
import android.net.Uri;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import tracewall.child.R;
import tracewall.child.block.BlocklistStore;
import tracewall.data.ListData;
import tracewall.data.RulesJson;

/** Android URI adapter. Callers must pass PinGate.require for exports and imports. */
public final class RulesTransfer {
    private static final ExecutorService EXECUTOR = tracewall.net.BoundedWorkPool.create("tracewall-backups", 1, 4);
    private RulesTransfer() {}
    public static void exportRules(Activity activity) {
        execute(activity, () -> {
            File file = null;
            try {
                String json = BlocklistStore.get().exportLocal(activity).serialize();
                // A fresh URI per export preserves older shared backups and never truncates one on failure.
                File directory = ShareFiles.target(activity, "rules.json").getParentFile();
                file = File.createTempFile("rules-", ".json", directory);
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(json.getBytes(StandardCharsets.UTF_8)); }
                File exported = file;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()
                            || (activity instanceof androidx.lifecycle.LifecycleOwner
                            && !((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().getCurrentState()
                                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))) {
                        exported.delete(); return;
                    }
                    ShareFiles.send(activity, exported, "application/json");
                });
            } catch (IOException | IllegalArgumentException | SecurityException e) {
                if (file != null) file.delete();
                failed(activity);
            }
        });
    }
    /** Call only after PinGate.require. The callback runs on the UI thread after a complete import. */
    public static void importRules(Activity activity, Uri uri) {
        importRules(activity, uri, () -> {});
    }
    public static void importRules(Activity activity, Uri uri, Runnable imported) {
        importRules(activity, uri, imported, () -> {});
    }
    public static void importRules(Activity activity, Uri uri, Runnable imported, Runnable rejected) {
        execute(activity, () -> {
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("No input");
                RulesJson rules = RulesJson.parse(ListData.utf8(ListData.read(input, RulesJson.MAX_BYTES)));
                BlocklistStore.get().importLocal(activity, rules);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Toast.makeText(activity, R.string.rules_imported, Toast.LENGTH_LONG).show();
                    imported.run();
                });
            } catch (IOException | IllegalArgumentException | SecurityException e) { failed(activity, rejected); }
        }, rejected);
    }
    private static void execute(Activity activity, Runnable work) {
        execute(activity, work, () -> {});
    }
    private static void execute(Activity activity, Runnable work, Runnable rejected) {
        try { EXECUTOR.execute(work); }
        catch (RejectedExecutionException overloaded) { failed(activity, rejected); }
    }
    private static void failed(Activity activity) {
        failed(activity, () -> {});
    }
    private static void failed(Activity activity, Runnable rejected) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, R.string.rules_failed, Toast.LENGTH_LONG).show();
                rejected.run();
            }
        });
    }
}
