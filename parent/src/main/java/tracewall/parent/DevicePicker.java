package tracewall.parent;

import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tracewall.parent.sync.FirebaseParent;

/** Local labels select a candidate; Firebase still decides whether access exists. */
final class DevicePicker {
    private static int generation;
    private DevicePicker() {}
    static void show(AppCompatActivity host, Runnable selected, Runnable add) {
        int ticket = ++generation;
        Map<String, String> names = ParentDevices.all(host).names();
        List<String> uids = new ArrayList<>(names.keySet());
        List<String> labels = new ArrayList<>();
        for (String uid : uids) labels.add(host.getString(R.string.named_child_label, names.get(uid), uid));
        labels.add(host.getString(R.string.device_add));
        new AlertDialog.Builder(host).setTitle(R.string.device_pick_title)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                if (which == uids.size()) { add.run(); return; }
                String uid = uids.get(which);
                Toast.makeText(host, R.string.device_verifying, Toast.LENGTH_SHORT).show();
                FirebaseParent.get().verifyAccess(uid, () -> {
                    if (ticket != generation || !active(host)) return;
                    try { ParentDevices.select(host, uid); selected.run(); }
                    catch (IllegalArgumentException forgotten) { Toast.makeText(host, R.string.device_switch_failed, Toast.LENGTH_LONG).show(); }
                }, () -> { if (ticket == generation && active(host)) Toast.makeText(host, R.string.device_switch_failed, Toast.LENGTH_LONG).show(); });
            }).setNegativeButton(android.R.string.cancel, (dialog, which) -> generation++)
            .setOnCancelListener(dialog -> generation++).show();
    }
    private static boolean active(AppCompatActivity host) {
        return !host.isFinishing() && !host.isDestroyed()
            && host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }
}
