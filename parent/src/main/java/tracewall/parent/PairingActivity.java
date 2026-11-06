package tracewall.parent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
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
    private boolean openingDashboard;
    private boolean pairingBusy;
    private static final String ACCOUNT_PREFS = "tracewall.account.ui";
    private static final String ACCOUNT_CHOICE = "account_choice_made";

    private final ActivityResultLauncher<Intent> account = registerForActivityResult(
        new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
            getSharedPreferences(ACCOUNT_PREFS, MODE_PRIVATE).edit().putBoolean(ACCOUNT_CHOICE, true).apply();
            updateAccountStatus();
            openSavedIfAuthenticated();
        });

    private final ActivityResultLauncher<ScanOptions> qrScanner =
        registerForActivityResult(new ScanContract(), this::onScanResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ui = ActivityPairingBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        ui.pairingToolbar.setNavigationOnClickListener(v -> leavePairing());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leavePairing(); }
        });

        ui.connectButton.setOnClickListener(v -> attemptPairing());
        ui.scanButton.setOnClickListener(v -> { if (requireAccount()) launchScanner(); });
        ui.accountButton.setOnClickListener(v -> openAccount(false));
        ui.savedDevices.setOnClickListener(v -> {
            if (!requireAccount()) return;
            DevicePicker.show(this, this::openDashboard, () -> {
            ui.childUidInput.setText(""); ui.pairingCodeInput.setText(""); ui.deviceName.setText("");
            });
        });
        updateAccountStatus();
        if (savedInstanceState == null && com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() == null
                && !getSharedPreferences(ACCOUNT_PREFS, MODE_PRIVATE).getBoolean(ACCOUNT_CHOICE, false)) {
            openAccount(true);
        } else openSavedIfAuthenticated();
    }

    @Override protected void onResume() { super.onResume(); updateAccountStatus(); }

    private void leavePairing() {
        // Do not abandon a distributed registration between cloud commit and local selection.
        if (pairingBusy) return;
        String saved = prefs().getString(KEY_CHILD_UID, null);
        if (getIntent().getBooleanExtra("add_device", false) && FirebaseParent.get().uid() != null
                && tracewall.security.PairingCode.validChildUid(saved)) openDashboard();
        else finish();
    }

    private void updateAccountStatus() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        ui.accountStatus.setText(user == null || FirebaseParent.get().uid() == null ? R.string.account_local
            : user.isAnonymous() ? R.string.account_guest : R.string.account_linked);
    }

    private boolean requireAccount() {
        if (FirebaseParent.get().uid() != null) return true;
        Toast.makeText(this, R.string.account_required, Toast.LENGTH_LONG).show();
        openAccount(false);
        return false;
    }

    private void openAccount(boolean initial) {
        account.launch(new Intent(this, tracewall.auth.AuthActivity.class)
            .putExtra(tracewall.auth.AuthActivity.EXTRA_ROLE, tracewall.auth.AuthActivity.ROLE_PARENT)
            .putExtra(tracewall.auth.AuthActivity.EXTRA_BOUND_UID, tracewall.auth.IdentityAnchor.get(this))
            .putExtra(tracewall.auth.AuthActivity.EXTRA_INITIAL, initial));
    }

    private void openSavedIfAuthenticated() {
        String saved = prefs().getString(KEY_CHILD_UID, null);
        if (FirebaseParent.get().uid() != null
                && tracewall.security.PairingCode.validChildUid(saved)
                && !getIntent().getBooleanExtra("add_device", false)) openDashboard();
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
            ui.childUidInput.setError(getString(R.string.pairing_uid_invalid)); return;
        }
        tracewall.security.PairingCode pairing;
        try { pairing = code.isEmpty() ? null : new tracewall.security.PairingCode(childUid, code); }
        catch (IllegalArgumentException e) {
            ui.pairingCodeInput.setError(getString(R.string.pairing_code_invalid));
            return;
        }
        if (!requireAccount()) return;
        if (childUid.equals(FirebaseParent.get().uid())) {
            ui.childUidInput.setError(getString(R.string.account_same_child));
            return;
        }
        try { ParentDevices.all(this).put(childUid, name.isEmpty() ? getString(R.string.device_default_name) : name); }
        catch (IllegalArgumentException invalid) { ui.deviceName.setError(getString(R.string.device_name_invalid)); return; }
        setBusy(true);
        android.content.Context app = getApplicationContext();
        Runnable connected = () -> {
            try { ParentDevices.remember(app, childUid, name); }
            catch (IllegalArgumentException invalid) {
                if (!isFinishing() && !isDestroyed()) {
                    setBusy(false);
                    ui.deviceName.setError(getString(R.string.device_name_invalid));
                }
                return;
            }
            if (isFinishing() || isDestroyed()) return;
            openDashboard();
        };
        java.util.function.Consumer<FirebaseParent.PairingFailure> failed = failure -> {
            if (isFinishing() || isDestroyed()) return;
            setBusy(false);
            int message;
            switch (failure) {
                case CHILD_RESET_REQUIRED: message = R.string.pairing_partial_reset; break;
                case SAME_IDENTITY: message = R.string.account_same_child; break;
                case INVALID_UID: message = R.string.pairing_uid_invalid; break;
                case NOT_FOUND: message = R.string.pairing_not_found; break;
                case PERMISSION: message = R.string.pairing_permission; break;
                case NETWORK: message = R.string.pairing_network; break;
                default: message = R.string.pairing_retry; break;
            }
            ui.pairingStatus.setText(message);
            ui.pairingStatus.setVisibility(View.VISIBLE);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        };

        if (pairing == null) FirebaseParent.get().verifyAccess(childUid, connected, failed);
        else FirebaseParent.get().registerAsParent(childUid, pairing.code, connected, failed);
    }
    private void setBusy(boolean busy) {
        pairingBusy = busy;
        ui.pairingToolbar.setNavigationIcon(busy ? null
            : androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this, tracewall.auth.R.drawable.ic_tw_arrow_back));
        ui.accountButton.setEnabled(!busy);
        ui.connectButton.setEnabled(!busy); ui.scanButton.setEnabled(!busy); ui.savedDevices.setEnabled(!busy);
        ui.childUidInput.setEnabled(!busy); ui.pairingCodeInput.setEnabled(!busy); ui.deviceName.setEnabled(!busy);
        ui.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        ui.pairingStatus.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) ui.pairingStatus.setText(R.string.parent_pairing_busy);
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
        if (openingDashboard || isFinishing() || isDestroyed()) return;
        if (!requireAccount()) return;
        openingDashboard = true;
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
