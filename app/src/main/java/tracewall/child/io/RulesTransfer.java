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

/** Android document/URI adapter; all parsing, validation and limits live in :blocklist. */
public final class RulesTransfer {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private RulesTransfer() {}
    public static void exportRules(Activity activity) {
        EXECUTOR.execute(() -> {
            try {
                String json = BlocklistStore.get().exportLocal(activity).serialize();
                File file = ShareFiles.target(activity, "rules.json");
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(json.getBytes(StandardCharsets.UTF_8)); }
                activity.runOnUiThread(() -> ShareFiles.send(activity, file, "application/json"));
            } catch (IOException | IllegalArgumentException e) { failed(activity); }
        });
    }
    public static void importRules(Activity activity, Uri uri) {
        EXECUTOR.execute(() -> {
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("No input");
                RulesJson rules = RulesJson.parse(ListData.utf8(ListData.read(input, RulesJson.MAX_BYTES)));
                BlocklistStore.get().importLocal(activity, rules);
                activity.runOnUiThread(() -> Toast.makeText(activity, R.string.rules_imported, Toast.LENGTH_LONG).show());
            } catch (IOException | IllegalArgumentException | SecurityException e) { failed(activity); }
        });
    }
    private static void failed(Activity activity) {
        activity.runOnUiThread(() -> Toast.makeText(activity, R.string.rules_failed, Toast.LENGTH_LONG).show());
    }
}
