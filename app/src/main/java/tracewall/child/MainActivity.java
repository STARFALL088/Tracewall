package tracewall.child;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.util.List;
import tracewall.child.block.BlocklistStore;
import tracewall.child.databinding.ActivityMainBinding;
import tracewall.child.log.AppLabels;
import tracewall.child.log.QueryLog;
import tracewall.child.log.QueryLogProvider;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.ui.AppBlockActivity;
import tracewall.child.ui.QueryAdapter;
import tracewall.child.ui.PinGate;
import tracewall.child.ui.TrendView;
import tracewall.child.vpn.DnsVpnService;

/**
 * One screen: turn protection on, watch what it is doing, export or clear what
 * it has seen.
 *
 * Polling rather than a bound service: the log writer is a worker pool inside
 * the service, and a 700 ms refresh is cheaper to reason about than an IPC
 * surface at this size. Events (start, stop, export, clear) still go through
 * real Intents and the ContentProvider.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TracewallLifecycle";
    private android.os.Parcelable pendingScroll;
    private static final long TICK_MS = 700;
    private static final long BUCKET_MS = 60_000L; // one trend bar = one minute

    private ActivityMainBinding ui;
    private QueryAdapter adapter;
    private AppLabels labels;
    private boolean updatingToggle;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    private final ActivityResultLauncher<String[]> rulesDocument = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) PinGate.require(this, () -> tracewall.child.io.RulesTransfer.importRules(this, uri));
        });

    private final ActivityResultLauncher<String> notificationPermission =
        registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {}
        );

    private final ActivityResultLauncher<Intent> consent =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startFiltering();
                } else {
                    updatingToggle = true;
                    ui.toggle.setChecked(false);
                    updatingToggle = false;
                    Toast.makeText(
                        this,
                        R.string.vpn_denied,
                        Toast.LENGTH_LONG
                    ).show();
                }
                render();
            }
        );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Inflate a new view tree; the VPN service has its own lifetime.
        android.util.Log.d(TAG, "MainActivity.onCreate");
        ui = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        labels = new AppLabels(getPackageManager());
        adapter = new QueryAdapter(labels, this::askRow);
        ui.list.setLayoutManager(new LinearLayoutManager(this));
        ui.list.setAdapter(adapter);
        adapter.setStateRestorationPolicy(androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        if (savedInstanceState != null) {
            pendingScroll = savedInstanceState.getParcelable("query_scroll");
            ui.queryFilter.setText(savedInstanceState.getString("query_filter", ""));
            int controlsY = savedInstanceState.getInt("controls_scroll", 0);
            ui.controls.post(() -> ui.controls.scrollTo(0, controlsY));
        }
        ui.queryFilter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) { render(); }
            public void afterTextChanged(android.text.Editable value) {}
        });

        ui.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updatingToggle) return;
            if (checked) requestStart();
            else {
                render();
                PinGate.require(this, this::stopFiltering);
            }
        });

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                "android.permission.POST_NOTIFICATIONS"
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(
                "android.permission.POST_NOTIFICATIONS"
            );
        }

        ui.actionApps.setOnClickListener(v ->
            startActivity(new Intent(this, AppBlockActivity.class))
        );
        ui.makePermanent.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(R.string.make_permanent)
            .setMessage(R.string.permanent_body)
            .setPositiveButton(android.R.string.ok, (d, w) ->
                openVpnSettings())
            .setNegativeButton(android.R.string.cancel, null).show());
        ui.exportRules.setOnClickListener(v -> PinGate.require(this, () -> tracewall.child.io.RulesTransfer.exportRules(this)));
        ui.importRules.setOnClickListener(v -> PinGate.require(this, () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
            if (intent.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, R.string.no_handler, Toast.LENGTH_LONG).show();
                return;
            }
            try { rulesDocument.launch(new String[] {"application/json", "text/json"}); }
            catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, R.string.no_handler, Toast.LENGTH_LONG).show(); }
        }));
        ui.updateLists.setOnClickListener(v -> updateLists());
        ui.manageAllows.setOnClickListener(v -> showAllows());
        PinGate.ensureSet(this);
        ui.copyCsv.setOnClickListener(v -> PinGate.require(this, this::copyCsv));
        ui.clearHistory.setOnClickListener(v -> confirmClearHistory());
        ui.deviceCode.setOnClickListener(v -> PinGate.require(this, this::showQrDialog));
        ui.manageParents.setOnClickListener(v -> PinGate.require(this, () ->
            FirebaseSync.get().listParents(parents -> new AlertDialog.Builder(this)
                .setTitle(R.string.revoke_parent)
                .setItems(parents.toArray(new String[0]), (d, which) ->
                    FirebaseSync.get().revokeParent(parents.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show())));
        ui.deviceCode.setOnLongClickListener(v -> {
            copyDeviceCode();
            return true;
        });

        // Keep the process log when this Activity is recreated or reopened.
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume visible-only polling and recheck OS settings.
        android.util.Log.d(TAG, "MainActivity.onResume");
        ticker.post(refresh);
        checkPrivateDns();
        updateDeviceCode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop polling while another screen owns the foreground.
        android.util.Log.d(TAG, "MainActivity.onPause");
        ticker.removeCallbacks(refresh);
    }

    @Override protected void onStart() {
        super.onStart();
        // The view is visible; rendering never starts the VPN.
        android.util.Log.d(TAG, "MainActivity.onStart");
    }
    @Override protected void onStop() {
        super.onStop();
        // Hiding this screen leaves the service running.
        android.util.Log.d(TAG, "MainActivity.onStop");
    }
    @Override protected void onRestart() {
        super.onRestart();
        // Return from a stopped screen without requesting VPN consent again.
        android.util.Log.d(TAG, "MainActivity.onRestart");
    }
    @Override protected void onDestroy() {
        // Remove pending UI callbacks; the service owns its own cleanup.
        android.util.Log.d(TAG, "MainActivity.onDestroy");
        ticker.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query_filter", ui.queryFilter.getText().toString());
        state.putParcelable("query_scroll", pendingScroll != null ? pendingScroll : ui.list.getLayoutManager().onSaveInstanceState());
        state.putInt("controls_scroll", ui.controls.getScrollY());
        super.onSaveInstanceState(state);
    }
    @Override protected void onRestoreInstanceState(Bundle state) {
        // Framework view restoration must never fire a protection toggle action.
        updatingToggle = true;
        super.onRestoreInstanceState(state);
        updatingToggle = false;
        render();
    }

    // ------------------------------------------------------- warnings / info

    /**
     * Warn when Private DNS (DoH/DoT) is enabled. Android resolves names via
     * an encrypted channel to a named server on port 853, which our TUN route
     * (10.66.0.1/32, UDP only) never sees. The warning appears on every
     * resume so it tracks the system setting in real time.
     */
    private void checkPrivateDns() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        String mode = Settings.Global.getString(
            getContentResolver(),
            "private_dns_mode"
        );
        boolean active = mode != null && !mode.equals("off");
        ui.privateDnsWarning.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    /** Show the Firebase UID as a tappable device code for pairing. */
    private void updateDeviceCode() {
        String uid = FirebaseSync.get().uid();
        if (uid == null) {
            ui.deviceCode.setVisibility(View.GONE);
            return;
        }
        ui.deviceCode.setText(getString(R.string.device_code_label, uid));
        ui.deviceCode.setVisibility(View.VISIBLE);
    }

    /** Tap: show the UID as a QR code the parent can scan directly. */
    private void showQrDialog() {
        String uid = FirebaseSync.get().uid();
        if (uid == null) return;
        FirebaseSync.get().createPairing(pairing -> showPairingQr(pairing), () ->
            Toast.makeText(this, R.string.pairing_create_failed, Toast.LENGTH_LONG).show());
    }

    private void showPairingQr(tracewall.security.PairingCode pairing) {
        if (isFinishing() || isDestroyed()) return;
        try {
            int size = 512;
            BitMatrix matrix = new MultiFormatWriter().encode(
                pairing.payload(),
                BarcodeFormat.QR_CODE,
                size,
                size
            );
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (
                int x = 0;
                x < size;
                x++
            ) pixels[y * size + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            Bitmap bmp = Bitmap.createBitmap(
                pixels,
                size,
                size,
                Bitmap.Config.ARGB_8888
            );
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setPadding(32, 32, 32, 32);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.qr_title)
                .setMessage(getString(R.string.pairing_details, pairing.childUid, pairing.code))
                .setView(iv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } catch (Exception e) {
            android.util.Log.e("Tracewall", "QR generation failed", e);
            copyDeviceCode(); // fall back to clipboard
        }
    }

    /** Long-press: copy the full UID to the clipboard as plain text. */
    private void copyDeviceCode() {
        String uid = FirebaseSync.get().uid();
        if (uid == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(
            Context.CLIPBOARD_SERVICE
        );
        if (cm != null) cm.setPrimaryClip(
            ClipData.newPlainText("device_code", uid)
        );
        Toast.makeText(
            this,
            R.string.device_code_copied,
            Toast.LENGTH_SHORT
        ).show();
    }

    // ------------------------------------------------------------ protection

    private void showAllows() {
        String[] hosts = BlocklistStore.get().localAllows(this).toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(R.string.manage_allows)
            .setItems(hosts, (d, which) -> PinGate.require(this, () -> {
                BlocklistStore.get().removeAllow(this, hosts[which]);
                render();
            }))
            .setMessage(hosts.length == 0 ? getString(R.string.no_allows) : null)
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void requestStart() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            consent.launch(prepare); // first run: system consent dialog
            return;
        }
        startFiltering();
    }

    private void startFiltering() {
        startService(
            new Intent(this, DnsVpnService.class).setAction(
                DnsVpnService.ACTION_START
            )
        );
        // establish() runs on the service thread; give it a moment so the
        // toggle does not flick back before the tunnel is actually up.
        ticker.postDelayed(this::render, 600);
    }

    private void stopFiltering() {
        startService(
            new Intent(this, DnsVpnService.class).setAction(
                DnsVpnService.ACTION_STOP
            )
        );
        render();
    }

    /**
     * Long-press flow, on any row: allow that host, or block the app that
     * queried it. A common parent flow is scrolling history, seeing TikTok
     * lookups, and deciding the whole app has to die - no rules list needed.
     *
     * Blocking is offered only for uid >= 10000: system uids belong to the
     * platform itself and blocking one would break parts of Android for no
     * parental gain.
     */
    private void askRow(QueryLog.Entry e) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle(e.host)
            .setMessage(getString(R.string.row_body, labels.label(e.uid)))
            .setPositiveButton(R.string.allow_yes, (dialog, which) -> {
                PinGate.require(this, () -> {
                    BlocklistStore.get().allow(this, e.host);
                    render();
                });
            });
        if (e.uid >= 10000) {
            b.setNegativeButton(R.string.block_app_yes, (dialog, which) -> {
                BlocklistStore.get().blockApp(this, e.uid);
                Toast.makeText(
                    this,
                    getString(R.string.app_blocked_toast, labels.label(e.uid)),
                    Toast.LENGTH_SHORT
                ).show();
                render();
            }).setNeutralButton(android.R.string.cancel, null);
        } else {
            b.setNegativeButton(android.R.string.cancel, null);
        }
        b.show();
    }

    // --------------------------------------------------------------- history

    /**
     * Read history through the ContentProvider rather than the in-memory ring,
     * so the export path exercises the same component a parent app would use.
     */
    private void copyCsv() {
        new Thread(() -> {
            try {
                java.io.File file = tracewall.child.io.ShareFiles.target(this, "history.csv");
                try (java.io.Writer writer = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8);
                     Cursor c = getContentResolver().query(QueryLogProvider.CONTENT_URI,
                         null, null, null, null)) {
                    writer.write("time_ms,host,type,blocked,uid,policy,latency_ms,resolver\r\n");
                    if (c == null) throw new java.io.IOException("History unavailable");
                    String[] columns = {"time_ms", "host", "type", "blocked", "uid", "policy", "latency_ms", "resolver"};
                    while (c.moveToNext()) {
                        Object[] row = new Object[columns.length];
                        for (int i = 0; i < columns.length; i++) row[i] = c.getString(c.getColumnIndexOrThrow(columns[i]));
                        writer.write(tracewall.data.CsvRow.of(row));
                    }
                }
                runOnUiThread(() -> tracewall.child.io.ShareFiles.send(this, file, "text/csv"));
            } catch (java.io.IOException | RuntimeException e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.csv_failed, Toast.LENGTH_LONG).show());
            }
        }, "tracewall-export").start();
    }

    private void openVpnSettings() {
        for (String action : new String[] {Settings.ACTION_VPN_SETTINGS, Settings.ACTION_SETTINGS}) {
            Intent intent = new Intent(action);
            if (intent.resolveActivity(getPackageManager()) == null) continue;
            try { startActivity(intent); return; }
            catch (android.content.ActivityNotFoundException ignored) { /* Try general settings. */ }
        }
        Toast.makeText(this, R.string.no_handler, Toast.LENGTH_LONG).show();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_body)
            .setPositiveButton(R.string.clear_yes, (dialog, which) ->
                clearHistory()
            )
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void clearHistory() {
        new Thread(() -> {
            int removed = 0;
            try {
                removed = getContentResolver().delete(
                    Uri.parse(QueryLogProvider.CONTENT_URI + "?limit=5000"),
                    null,
                    null
                );
            } catch (RuntimeException ignored) {
                // the toast reports what actually happened, including a failure
            }
            final int count = removed;
            runOnUiThread(() -> {
                QueryLog.get().clear();
                Toast.makeText(
                    this,
                    getString(R.string.clear_done, count),
                    Toast.LENGTH_SHORT
                ).show();
            });
        }, "tracewall-clear").start();
    }

    private boolean listUpdateRunning;
    private void updateLists() {
        if (listUpdateRunning) return;
        listUpdateRunning = true;
        ui.updateLists.setEnabled(false);
        tracewall.child.net.ListUpdater.update(this, new tracewall.child.net.ListUpdater.Listener() {
            public void progress(int completed, int total) {
                if (!isDestroyed()) ui.updateResult.setText(getString(R.string.update_progress, completed, total));
            }
            public void done(tracewall.child.net.ListUpdater.Result result) {
                listUpdateRunning = false;
                if (isDestroyed()) return;
                ui.updateLists.setEnabled(true);
                render();
            }
        });
    }
    private void renderUpdateResult() {
        if (listUpdateRunning) return;
        android.content.SharedPreferences sp = tracewall.child.net.ListUpdater.prefs(this);
        long attempt = sp.getLong("attempt_ms", 0), success = sp.getLong("success_ms", 0);
        if (attempt == 0) { ui.updateResult.setText(R.string.update_never); return; }
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        ui.updateResult.setText(getString(R.string.update_result, sp.getInt("updated", 0), sp.getInt("failed", 0),
            sp.getInt("added", 0), format.format(new java.util.Date(attempt)),
            success == 0 ? getString(R.string.update_never_success) : format.format(new java.util.Date(success))));
    }

    // ---------------------------------------------------------------- render

    private void render() {
        // Anonymous sign-in can finish after onResume; show pairing as soon as it is ready.
        updateDeviceCode();
        boolean on = DnsVpnService.isRunning();
        updatingToggle = true;
        if (ui.toggle.isChecked() != on) ui.toggle.setChecked(on);
        updatingToggle = false;

        ui.updateSchedule.setText(BlocklistStore.isEnabled(this) ? R.string.update_scheduled : R.string.update_not_scheduled);
        renderUpdateResult();
        ui.status.setText(on ? R.string.status_on : R.string.status_off);

        QueryLog.Stats s = QueryLog.get().stats();
        // Rule count comes from the live policy, not the service's boot-time
        // snapshot: allowing a host changes it without restarting the tunnel.
        ui.detail.setText(
            getString(
                R.string.detail,
                s.total,
                s.blocked,
                BlocklistStore.get().size()
            )
        );

        List<QueryLog.Entry> recent = QueryLog.get().snapshot(500);
        List<QueryLog.Entry> visible = new java.util.ArrayList<>();
        String filter = ui.queryFilter.getText().toString();
        for (QueryLog.Entry entry : recent) {
            if (tracewall.data.TextFilter.matches(filter, entry.host, labels.label(entry.uid))) visible.add(entry);
            if (visible.size() == 60) break;
        }
        adapter.submit(visible);
        if (pendingScroll != null && !visible.isEmpty()) {
            ui.list.getLayoutManager().onRestoreInstanceState(pendingScroll);
            pendingScroll = null;
        }
        updateTrend(recent);
    }

    /** Bucket the visible history into one bar per minute, oldest on the left. */
    private void updateTrend(List<QueryLog.Entry> recent) {
        int n = TrendView.buckets();
        int[] allowed = new int[n];
        int[] blocked = new int[n];
        long now = System.currentTimeMillis();

        for (QueryLog.Entry e : recent) {
            long age = now - e.timeMs;
            if (age < 0) age = 0;
            int idx = n - 1 - (int) (age / BUCKET_MS);
            if (idx < 0 || idx >= n) continue;
            if (e.blocked) blocked[idx]++;
            else allowed[idx]++;
        }
        ui.trend.setSeries(allowed, blocked);
    }
}
