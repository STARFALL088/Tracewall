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
        if (saved != null && !saved.isEmpty()) {
            openDashboard();
            return;
        }

        ui = ActivityPairingBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        ui.connectButton.setOnClickListener(v -> attemptPairing());
        ui.scanButton.setOnClickListener(v -> launchScanner());
    }

    private void attemptPairing() {
        String childUid = ui.childUidInput.getText().toString().trim();
        if (TextUtils.isEmpty(childUid)) {
            ui.childUidInput.setError(getString(R.string.pairing_uid_empty));
            return;
        }

        ui.connectButton.setEnabled(false);
        ui.progress.setVisibility(View.VISIBLE);

        FirebaseParent.get().registerAsParent(
            childUid,
            /* onSuccess */ () -> {
                prefs().edit().putString(KEY_CHILD_UID, childUid).apply();
                openDashboard();
            },
            /* onFail */ () -> {
                ui.connectButton.setEnabled(true);
                ui.progress.setVisibility(View.GONE);
                Toast.makeText(
                    this,
                    R.string.pairing_failed,
                    Toast.LENGTH_LONG
                ).show();
            }
        );
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
            ui.childUidInput.setText(content);
            attemptPairing();
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
