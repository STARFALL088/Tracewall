package tracewall.child.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;
import tracewall.child.R;
import tracewall.child.databinding.ActivityPrivacyBinding;
import tracewall.child.log.HistoryWriter;
import tracewall.child.sync.FirebaseSync;
import tracewall.net.BoundedWorkPool;
import tracewall.privacy.PrivacyPolicy;

/** Read-only until each individual change/deletion is authorized with the existing PIN gate. */
public class PrivacyActivity extends AppCompatActivity {
    private static final int[] DAYS = {0, 1, 3, 7};
    private static final int[] DAY_LABELS = {R.string.privacy_retention_none, R.string.privacy_retention_1,
        R.string.privacy_retention_3, R.string.privacy_retention_7};
    private ActivityPrivacyBinding ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor io = BoundedWorkPool.create("tracewall-privacy-ui", 1, 1);
    private FirebaseSync sync;
    private boolean busy;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { render(); main.postDelayed(this, 1000); }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityPrivacyBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        sync = FirebaseSync.get();
        ui.privacyBack.setOnClickListener(v -> finish());
        ui.privacyMode.setOnClickListener(v -> {
            boolean enable = !sync.isCloudEnabled();
            confirm(enable ? R.string.privacy_use_cloud : R.string.privacy_use_local,
                enable ? R.string.privacy_cloud_confirm : R.string.privacy_local_confirm,
                () -> {
                    if (!enable) tracewall.child.location.LocationSharingService.stop(this);
                    changed(sync.setCloudEnabled(enable));
                });
        });
        ui.privacyHistorySharing.setOnClickListener(v -> {
            boolean enable = !sync.isHistorySharingEnabled();
            confirm(R.string.privacy_sharing_title, R.string.privacy_history_confirm,
                () -> changed(sync.setHistorySharingEnabled(enable)));
        });
        ui.privacyInventorySharing.setOnClickListener(v -> {
            boolean enable = !sync.isInventorySharingEnabled();
            confirm(R.string.privacy_sharing_title, R.string.privacy_inventory_confirm,
                () -> changed(sync.setInventorySharingEnabled(enable)));
        });
        ui.privacyRetryNetwork.setOnClickListener(v -> PinGate.require(this, () -> { sync.retryDatabaseMode(); render(); }));
        ui.privacyRetention.setOnClickListener(v -> chooseRetention());
        ui.privacyClearLocal.setOnClickListener(v -> confirm(R.string.privacy_clear_local, R.string.privacy_clear_confirm,
            () -> storageAction(() -> getString(R.string.privacy_clear_done, HistoryWriter.get().clearStored(getApplicationContext())))));
        ui.privacyDeleteCloud.setOnClickListener(v -> confirm(R.string.privacy_cloud_delete_title, R.string.privacy_delete_confirm, () -> {
            tracewall.child.location.LocationSharingService.stop(this);
            sync.deleteCloudUploads(result -> {
                if (!alive()) return;
                ui.privacyResult.setText(result == FirebaseSync.DeleteResult.CONFIRMED ? R.string.privacy_delete_confirmed
                    : result == FirebaseSync.DeleteResult.IDENTITY_UNAVAILABLE ? R.string.privacy_identity_unavailable : R.string.privacy_delete_pending);
                render();
            });
            render();
        }));
        if (state != null) {
            ui.privacyResult.setText(state.getString("result", ""));
            int scroll = state.getInt("scroll", 0);
            ui.privacyScroll.post(() -> ui.privacyScroll.scrollTo(0, scroll));
        }
        render();
    }
    private void confirm(int title, int message, Runnable approved) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(R.string.privacy_apply, (dialog, which) -> PinGate.require(this, approved))
            .setNegativeButton(android.R.string.cancel, null).show();
    }
    private void changed(boolean accepted) {
        if (!accepted) ui.privacyResult.setText(R.string.privacy_delete_pending);
        else ui.privacyResult.setText("");
        render();
    }
    private void chooseRetention() {
        String[] labels = new String[DAY_LABELS.length];
        int current = HistoryWriter.get().retentionDays(this), selected = 0;
        for (int i = 0; i < labels.length; i++) { labels[i] = getString(DAY_LABELS[i]); if (DAYS[i] == current) selected = i; }
        int[] choice = {selected};
        new AlertDialog.Builder(this).setTitle(R.string.privacy_retention_title)
            .setSingleChoiceItems(labels, selected, (dialog, which) -> choice[0] = which)
            .setPositiveButton(R.string.privacy_apply, (dialog, which) -> PinGate.require(this, () -> storageAction(() -> {
                HistoryWriter.get().setRetentionDays(getApplicationContext(), DAYS[choice[0]]);
                return getString(R.string.privacy_retention_saved);
            }))).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void storageAction(Supplier<String> action) {
        if (busy || !alive()) return;
        busy = true;
        ui.privacyResult.setText("");
        render();
        try {
            io.execute(() -> {
                String message;
                try { message = action.get(); }
                catch (RuntimeException error) { message = getString(R.string.privacy_action_failed); }
                String result = message;
                runOnUiThread(() -> {
                    if (!alive()) return;
                    busy = false;
                    ui.privacyResult.setText(result);
                    render();
                });
            });
        } catch (RejectedExecutionException error) {
            busy = false;
            ui.privacyResult.setText(R.string.privacy_action_failed);
            render();
        }
    }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void render() {
        PrivacyPolicy policy = sync.privacyPolicy();
        FirebaseSync.NetworkState network = sync.networkState();
        int status;
        switch (network) {
            case LOCAL: status = R.string.privacy_local_state; break;
            case CLOUD: status = R.string.privacy_cloud_state; break;
            case CONNECTING: status = R.string.privacy_connecting; break;
            case IDENTITY_REQUIRED: status = R.string.privacy_identity_required; break;
            case DELETING: status = R.string.privacy_deleting; break;
            case DELETE_PENDING: status = R.string.privacy_delete_pending; break;
            case ERROR: status = R.string.privacy_network_error; break;
            default: status = R.string.privacy_stopping;
        }
        ui.privacyStatus.setText(status);
        ui.privacyMode.setText(policy.allowsCloud() ? R.string.privacy_use_local : R.string.privacy_use_cloud);
        ui.privacyHistorySharing.setText(policy.history ? R.string.privacy_history_on : R.string.privacy_history_off);
        ui.privacyInventorySharing.setText(policy.inventory ? R.string.privacy_inventory_on : R.string.privacy_inventory_off);
        int days = HistoryWriter.get().retentionDays(this), label = DAY_LABELS[3];
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i] == days) label = DAY_LABELS[i];
        ui.privacyRetention.setText(getString(R.string.privacy_retention_label, getString(label)));
        boolean sharingChanges = !busy && !policy.deletionPending;
        ui.privacyMode.setEnabled(sharingChanges);
        ui.privacyHistorySharing.setEnabled(sharingChanges);
        ui.privacyInventorySharing.setEnabled(sharingChanges);
        ui.privacyRetention.setEnabled(!busy);
        ui.privacyClearLocal.setEnabled(!busy);
        ui.privacyRetryNetwork.setEnabled(!busy && network != FirebaseSync.NetworkState.DELETING);
        ui.privacyDeleteCloud.setEnabled(!busy && network != FirebaseSync.NetworkState.DELETING && network != FirebaseSync.NetworkState.STOPPING);
        ui.privacyDeleteCloud.setText(policy.deletionPending ? R.string.privacy_retry_delete : R.string.privacy_delete_cloud);
        long verified = sync.lastConfirmedDeletionMs();
        ui.privacyDeletionStatus.setText(policy.deletionPending ? getString(R.string.privacy_delete_pending)
            : verified == 0 ? getString(R.string.privacy_delete_never)
            : getString(R.string.privacy_delete_verified_at, DateFormat.getDateTimeInstance().format(new Date(verified))));
        ui.privacyProgress.setVisibility(busy || network == FirebaseSync.NetworkState.DELETING ? View.VISIBLE : View.GONE);
    }
    @Override protected void onResume() { super.onResume(); main.post(ticker); }
    @Override protected void onPause() { main.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onDestroy() {
        main.removeCallbacks(ticker);
        // Already PIN-approved storage work finishes even on rotation; never interrupt a durable write halfway.
        io.shutdown();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("scroll", ui.privacyScroll.getScrollY());
        state.putString("result", ui.privacyResult.getText().toString());
        super.onSaveInstanceState(state);
    }
}
