package tracewall.parent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;
import tracewall.parent.databinding.ActivityPairingBinding;
import tracewall.parent.sync.FirebaseParent;

/**
 * First-run screen: the parent enters the child's device code (its Firebase
 * UID, shown on the child's main screen) to establish a pairing.
 *
 * On success the parent UID is written to the child's RTDB node so the
 * security rules will grant the parent access from that point on, and the
 * paired child UID is saved to SharedPreferences so the app opens directly
 * to the dashboard on subsequent launches.
 */
public class PairingActivity extends AppCompatActivity {

    public static final String PREFS = "tracewall.parent.prefs";
    public static final String KEY_CHILD_UID = "child_uid";

    private ActivityPairingBinding ui;

    private final ActivityResultLauncher<ScanOptions> qrScanner =
        registerForActivityResult(new ScanContract(), this::onScanResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Skip pairing if already paired.
        String saved = prefs().getString(KEY_CHILD_UID, null);
        if (tracewall.security.PairingCode.validChildUid(saved) && !getIntent().getBooleanExtra("add_device", false)) {
            openDashboard();
            return;
        }

        ui = ActivityPairingBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        ui.connectButton.setOnClickListener(v -> attemptPairing());
        ui.scanButton.setOnClickListener(v -> launchScanner());
        ui.savedDevices.setOnClickListener(v -> DevicePicker.show(this, this::openDashboard, () -> {
            ui.childUidInput.setText(""); ui.pairingCodeInput.setText(""); ui.deviceName.setText("");
        }));
    }

    private void attemptPairing() {
        String childUid = ui.childUidInput.getText().toString().trim();
        if (TextUtils.isEmpty(childUid)) {
            ui.childUidInput.setError(getString(R.string.pairing_uid_empty));
            return;
        }

        String code = ui.pairingCodeInput.getText().toString().trim();
        String name = ui.deviceName.getText().toString().trim();
        if (!tracewall.security.PairingCode.validChildUid(childUid)) {
            ui.childUidInput.setError(getString(R.string.pairing_code_invalid)); return;
        }
        tracewall.security.PairingCode pairing;
        try { pairing = code.isEmpty() ? null : new tracewall.security.PairingCode(childUid, code); }
        catch (IllegalArgumentException e) {
            ui.pairingCodeInput.setError(getString(R.string.pairing_code_invalid));
            return;
        }
        try { ParentDevices.all(this).put(childUid, name.isEmpty() ? getString(R.string.device_default_name) : name); }
        catch (IllegalArgumentException invalid) { ui.deviceName.setError(getString(R.string.device_name_invalid)); return; }
        setBusy(true);
        Runnable connected = () -> {
            if (isFinishing() || isDestroyed()) return;
            try { ParentDevices.remember(this, childUid, name); openDashboard(); }
            catch (IllegalArgumentException invalid) { setBusy(false); ui.deviceName.setError(getString(R.string.device_name_invalid)); }
        };
        java.util.function.Consumer<FirebaseParent.PairingFailure> failed = failure -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            Toast.makeText(this, failure == FirebaseParent.PairingFailure.CHILD_RESET_REQUIRED
                ? R.string.pairing_partial_reset : R.string.pairing_retry, Toast.LENGTH_LONG).show();
        };

        if (pairing == null) FirebaseParent.get().verifyAccess(childUid, connected,
            () -> failed.accept(FirebaseParent.PairingFailure.CONNECTION));
        else FirebaseParent.get().registerAsParent(childUid, pairing.code, connected, failed);
    }
    private void setBusy(boolean busy) {
        ui.connectButton.setEnabled(!busy); ui.scanButton.setEnabled(!busy); ui.savedDevices.setEnabled(!busy);
        ui.childUidInput.setEnabled(!busy); ui.pairingCodeInput.setEnabled(!busy); ui.deviceName.setEnabled(!busy);
        ui.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void launchScanner() {
        ScanOptions opts = new ScanOptions()
            .setPrompt(getString(R.string.pairing_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false);
        qrScanner.launch(opts);
    }

    private void onScanResult(ScanIntentResult result) {
        String content = result.getContents();
        if (content != null && !content.isEmpty()) {
            try {
                tracewall.security.PairingCode pairing = tracewall.security.PairingCode.parse(content);
                ui.childUidInput.setText(pairing.childUid);
                ui.pairingCodeInput.setText(pairing.code);
                attemptPairing();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, R.string.pairing_code_invalid, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
