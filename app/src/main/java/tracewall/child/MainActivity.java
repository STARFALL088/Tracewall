package tracewall.child;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.activity.OnBackPressedCallback;
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
import tracewall.child.databinding.PageActivityBinding;
import tracewall.child.databinding.PageFamilyBinding;
import tracewall.child.databinding.PageHomeBinding;
import tracewall.child.databinding.PageProtectBinding;
import tracewall.child.databinding.PageSettingsBinding;
import tracewall.child.log.AppLabels;
import tracewall.child.log.QueryLog;
import tracewall.child.log.QueryLogProvider;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.ui.AppBlockActivity;
import tracewall.child.ui.QueryAdapter;
import tracewall.child.ui.PinGate;
import tracewall.child.ui.TrendView;
import tracewall.child.ui.RulesActivity;
import tracewall.child.vpn.DnsVpnService;

/**
 * Five focused destinations share the live service state, not a crowded screen.
 * Each page keeps its own scroll position; opening a page never starts the VPN.
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
    private PageHomeBinding home;
    private PageActivityBinding feed;
    private PageProtectBinding protect;
    private PageFamilyBinding family;
    private PageSettingsBinding settings;
    private int selectedPage = R.id.nav_home;
    private final int[] pageScrollPositions = new int[4];
    private final boolean[] restorePageScroll = new boolean[4];
    private OnBackPressedCallback pageBack;
    private QueryAdapter adapter;
    private AppLabels labels;
    private boolean updatingToggle;
    private boolean awaitingInitialAccount;
    private static final String ACCOUNT_PREFS = "tracewall.account.ui";
    private static final String ACCOUNT_CHOICE = "account_choice_made";

    private final ActivityResultLauncher<Intent> account = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            awaitingInitialAccount = false;
            getSharedPreferences(ACCOUNT_PREFS, MODE_PRIVATE).edit().putBoolean(ACCOUNT_CHOICE, true).apply();
            PinGate.ensureSet(this);
            updateAccountStatus();
            updateDeviceCode();
        });

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ticker.postDelayed(this, TICK_MS);
        }
    };
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
                    home.toggle.setChecked(false);
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
        home = ui.homePage;
        feed = ui.activityPage;
        protect = ui.protectPage;
        family = ui.familyPage;
        settings = ui.settingsPage;
        awaitingInitialAccount = savedInstanceState != null
            && savedInstanceState.getBoolean("account_initial_pending", false);

        labels = new AppLabels(getPackageManager());
        adapter = new QueryAdapter(labels, this::askRow);
        feed.list.setLayoutManager(new LinearLayoutManager(this));
        feed.list.setAdapter(adapter);
        adapter.setStateRestorationPolicy(androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        if (savedInstanceState != null) {
            pendingScroll = savedInstanceState.getParcelable("query_scroll");
            feed.queryFilter.setText(savedInstanceState.getString("query_filter", ""));
            readPageScroll(savedInstanceState);
        }
        feed.queryFilter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) { render(); }
            public void afterTextChanged(android.text.Editable value) {}
        });

        home.toggle.setOnCheckedChangeListener((button, checked) -> {
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

        protect.actionApps.setOnClickListener(v ->
            startActivity(new Intent(this, AppBlockActivity.class))
        );
        settings.makePermanent.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(R.string.make_permanent)
            .setMessage(R.string.permanent_body)
            .setPositiveButton(android.R.string.ok, (d, w) ->
                openVpnSettings())
            .setNegativeButton(android.R.string.cancel, null).show());
        protect.updateLists.setOnClickListener(v -> updateLists());
        feed.openHistory.setOnClickListener(v -> startActivity(new Intent(this, tracewall.child.ui.HistoryActivity.class)));
        protect.openRules.setOnClickListener(v -> openRules(RulesActivity.PAGE_DOMAINS));
        protect.openSchedules.setOnClickListener(v -> openRules(RulesActivity.PAGE_SCHEDULES));
        settings.openBackup.setOnClickListener(v -> openRules(RulesActivity.PAGE_BACKUP));
        settings.accountButton.setOnClickListener(v -> PinGate.require(this, () -> openAccount(false)));
        family.familyAccount.setOnClickListener(v -> PinGate.require(this, () -> openAccount(false)));
        home.homeActivity.setOnClickListener(v -> ui.navigation.setSelectedItemId(R.id.nav_activity));
        home.homeProtection.setOnClickListener(v -> ui.navigation.setSelectedItemId(R.id.nav_protect));
        home.privateDnsSettings.setOnClickListener(v -> openPrivateDnsSettings());
        feed.clearSearch.setOnClickListener(v -> feed.queryFilter.setText(""));
        feed.emptyAction.setOnClickListener(v -> {
            if (feed.queryFilter.length() > 0) feed.queryFilter.setText("");
            else ui.navigation.setSelectedItemId(R.id.nav_home);
        });
        wireNavigation(savedInstanceState);
        if (!awaitingInitialAccount && com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() == null
                && !PinGate.isConfigured(this)
                && !getSharedPreferences(ACCOUNT_PREFS, MODE_PRIVATE).getBoolean(ACCOUNT_CHOICE, false)) {
            openAccount(true);
        } else if (!awaitingInitialAccount) PinGate.ensureSet(this);
        updateAccountStatus();
        settings.clearHistory.setOnClickListener(v -> PinGate.require(this, this::confirmClearHistory));
        family.deviceCode.setOnClickListener(v -> PinGate.require(this, this::showQrDialog));
        family.shareAppNames.setText(FirebaseSync.get().isInventorySharingEnabled() ? R.string.inventory_on : R.string.inventory_off);
        family.shareAppNames.setOnClickListener(v -> PinGate.require(this, () -> configureInventorySharing(null)));
        family.manageParents.setOnClickListener(v -> PinGate.require(this, this::manageParents));
        family.deviceCode.setOnLongClickListener(v -> {
            PinGate.require(this, this::copyDeviceCode);
            return true;
        });

        // Keep the process log when this Activity is recreated or reopened.
        render();
    }

    private void wireNavigation(Bundle state) {
        pageBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                ui.navigation.setSelectedItemId(R.id.nav_home);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, pageBack);
        ui.navigation.setOnItemSelectedListener(item -> {
            showPage(item.getItemId());
            return true;
        });
        int restored = state == null ? R.id.nav_home : state.getInt("selected_page", R.id.nav_home);
        if (ui.navigation.getMenu().findItem(restored) == null) restored = R.id.nav_home;
        ui.navigation.setSelectedItemId(restored);
        showPage(restored);
    }

    private void showPage(int id) {
        int[] ids = {R.id.nav_home, R.id.nav_activity, R.id.nav_protect, R.id.nav_family, R.id.nav_settings};
        int[] titles = {R.string.nav_home_title, R.string.nav_activity_title, R.string.nav_protect_title,
            R.string.nav_family_title, R.string.nav_settings_title};
        int[] subtitles = {R.string.nav_home_subtitle, R.string.nav_activity_subtitle, R.string.nav_protect_subtitle,
            R.string.nav_family_subtitle, R.string.nav_settings_subtitle};
        View[] pages = {home.getRoot(), feed.getRoot(), protect.getRoot(), family.getRoot(), settings.getRoot()};
        for (int i = 0; i < ids.length; i++) {
            boolean selected = ids[i] == id;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) {
                ui.pageTitle.setText(titles[i]);
                ui.pageSubtitle.setText(subtitles[i]);
            }
        }
        if (selectedPage != id) {
            View focused = getCurrentFocus();
            if (focused != null) {
                android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                focused.clearFocus();
            }
        }
        selectedPage = id;
        pageBack.setEnabled(id != R.id.nav_home);
        restoreSelectedPageScroll(id);
    }

    private android.widget.ScrollView[] scrollPages() {
        return new android.widget.ScrollView[]{home.getRoot(), protect.getRoot(), family.getRoot(), settings.getRoot()};
    }

    private int scrollPageIndex(int id) {
        if (id == R.id.nav_protect) return 1;
        if (id == R.id.nav_family) return 2;
        if (id == R.id.nav_settings) return 3;
        return id == R.id.nav_home ? 0 : -1;
    }

    private void readPageScroll(Bundle state) {
        for (int i = 0; i < pageScrollPositions.length; i++) {
            pageScrollPositions[i] = state.getInt("page_scroll_" + i, 0);
            restorePageScroll[i] = true;
        }
    }

    private void restoreSelectedPageScroll(int id) {
        int index = scrollPageIndex(id);
        if (index < 0 || !restorePageScroll[index]) return;
        android.widget.ScrollView page = scrollPages()[index];
        int scroll = pageScrollPositions[index];
        restorePageScroll[index] = false;
        page.post(() -> {
            if (!isFinishing() && !isDestroyed() && scrollPageIndex(selectedPage) == index)
                page.scrollTo(0, scroll);
        });
    }

    private void openRules(int page) {
        startActivity(new Intent(this, RulesActivity.class).putExtra(RulesActivity.EXTRA_PAGE, page));
    }

    private void manageParents() {
        FirebaseSync.get().listParents(parents -> {
            if (isFinishing() || isDestroyed()) return;
            if (parents.isEmpty()) {
                new AlertDialog.Builder(this).setTitle(R.string.nav_no_parents_title)
                    .setMessage(R.string.nav_no_parents_body).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle(R.string.revoke_parent)
                .setItems(parents.toArray(new String[0]), (d, which) -> {
                    Toast.makeText(this, R.string.revoke_pending, Toast.LENGTH_LONG).show();
                    FirebaseSync.get().revokeParent(parents.get(which), () -> {
                        if (!isDestroyed()) Toast.makeText(this, R.string.revoke_confirmed, Toast.LENGTH_LONG).show();
                    }, () -> {
                        if (!isDestroyed()) Toast.makeText(this, R.string.revoke_pending, Toast.LENGTH_LONG).show();
                    });
                }).setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resume visible-only polling and recheck OS settings.
        android.util.Log.d(TAG, "MainActivity.onResume");
        ticker.post(refresh);
        checkPrivateDns();
        updateDeviceCode();
        updateAccountStatus();
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
        state.putString("query_filter", feed.queryFilter.getText().toString());
        state.putBoolean("account_initial_pending", awaitingInitialAccount);
        state.putParcelable("query_scroll", pendingScroll != null ? pendingScroll : feed.list.getLayoutManager().onSaveInstanceState());
        state.putInt("selected_page", selectedPage);
        android.widget.ScrollView[] pages = scrollPages();
        for (int i = 0; i < pages.length; i++) {
            if (!restorePageScroll[i]) pageScrollPositions[i] = pages[i].getScrollY();
            state.putInt("page_scroll_" + i, pageScrollPositions[i]);
        }
        super.onSaveInstanceState(state);
    }
    @Override protected void onRestoreInstanceState(Bundle state) {
        // Framework view restoration must never fire a protection toggle action.
        updatingToggle = true;
        super.onRestoreInstanceState(state);
        updatingToggle = false;
        showPage(selectedPage);
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
        home.privateDnsWarning.setVisibility(active ? View.VISIBLE : View.GONE);
        home.privateDnsSettings.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private void openAccount(boolean initial) {
        if (initial) awaitingInitialAccount = true;
        account.launch(new Intent(this, tracewall.auth.AuthActivity.class)
            .putExtra(tracewall.auth.AuthActivity.EXTRA_ROLE, tracewall.auth.AuthActivity.ROLE_CHILD)
            .putExtra(tracewall.auth.AuthActivity.EXTRA_BOUND_UID, tracewall.auth.IdentityAnchor.get(this))
            .putExtra(tracewall.auth.AuthActivity.EXTRA_INITIAL, initial));
    }

    private void updateAccountStatus() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        int status = user == null || FirebaseSync.get().uid() == null ? R.string.account_local
            : user.isAnonymous() ? R.string.account_guest : R.string.account_linked;
        family.accountStatus.setText(status);
        settings.settingsAccountStatus.setText(status);
    }

    /** Pairing stays visible with an explanation when no account is connected. */
    private void updateDeviceCode() {
        String uid = FirebaseSync.get().uid();
        boolean available = uid != null;
        family.deviceCode.setEnabled(available);
        family.manageParents.setEnabled(available);
        family.pairingAvailability.setVisibility(available ? View.GONE : View.VISIBLE);
        family.deviceIdentity.setVisibility(available ? View.VISIBLE : View.GONE);
        if (available) family.deviceIdentity.setText(getString(R.string.nav_pair_identity, uid));
        family.familyAccount.setText(available ? R.string.nav_account_action : R.string.nav_pair_account);
    }

    /** Tap: show the UID as a QR code the parent can scan directly. */
    private void showQrDialog() {
        String uid = FirebaseSync.get().uid();
        if (uid == null) return;
        configureInventorySharing(() -> FirebaseSync.get().createPairing(pairing -> showPairingQr(pairing), () ->
            Toast.makeText(this, R.string.pairing_create_failed, Toast.LENGTH_LONG).show()));
    }

    private void configureInventorySharing(Runnable after) {
        android.widget.CheckBox share = new android.widget.CheckBox(this);
        share.setText(R.string.inventory_consent);
        share.setChecked(FirebaseSync.get().isInventorySharingEnabled());
        new AlertDialog.Builder(this).setTitle(R.string.pairing_share_title)
            .setMessage(R.string.pairing_share_body).setView(share)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                FirebaseSync.get().setInventorySharingEnabled(share.isChecked());
                family.shareAppNames.setText(share.isChecked() ? R.string.inventory_on : R.string.inventory_off);
                if (after != null) after.run();
            }).setNegativeButton(android.R.string.cancel, null).show();
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
    private void requestStart() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            consent.launch(prepare); // first run: system consent dialog
            return;
        }
        startFiltering();
    }

    private void startFiltering() {
        ContextCompat.startForegroundService(this,
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
                    if (!BlocklistStore.get().allow(this, e.host))
                        Toast.makeText(this, R.string.rules9_edit_failed, Toast.LENGTH_LONG).show();
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

    private void openPrivateDnsSettings() {
        Intent intent = new Intent("android.settings.PRIVATE_DNS_SETTINGS");
        if (intent.resolveActivity(getPackageManager()) != null) {
            try { startActivity(intent); return; }
            catch (android.content.ActivityNotFoundException ignored) { /* Use general settings. */ }
        }
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (android.content.ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.no_handler, Toast.LENGTH_LONG).show();
        }
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
            .setMessage(R.string.nav_clear_help)
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
        protect.updateLists.setEnabled(false);
        tracewall.child.net.ListUpdater.update(this, new tracewall.child.net.ListUpdater.Listener() {
            public void progress(int completed, int total) {
                if (!isDestroyed()) protect.updateResult.setText(getString(R.string.update_progress, completed, total));
            }
            public void done(tracewall.child.net.ListUpdater.Result result) {
                listUpdateRunning = false;
                if (isDestroyed()) return;
                protect.updateLists.setEnabled(true);
                render();
            }
        });
    }
    private void renderUpdateResult() {
        if (listUpdateRunning) return;
        android.content.SharedPreferences sp = tracewall.child.net.ListUpdater.prefs(this);
        long attempt = sp.getLong("attempt_ms", 0), success = sp.getLong("success_ms", 0);
        if (attempt == 0) { protect.updateResult.setText(R.string.update_never); return; }
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        protect.updateResult.setText(getString(R.string.update_result, sp.getInt("updated", 0), sp.getInt("failed", 0),
            sp.getInt("added", 0), format.format(new java.util.Date(attempt)),
            success == 0 ? getString(R.string.update_never_success) : format.format(new java.util.Date(success))));
    }

    // ---------------------------------------------------------------- render

    private void render() {
        // Account changes can finish after onResume; reflect them without changing identity.
        updateDeviceCode();
        updateAccountStatus();
        boolean on = DnsVpnService.isRunning();
        boolean starting = !on && DnsVpnService.isStarting();
        updatingToggle = true;
        if (home.toggle.isChecked() != (on || starting)) home.toggle.setChecked(on || starting);
        updatingToggle = false;

        protect.updateSchedule.setText(BlocklistStore.isEnabled(this) ? R.string.update_scheduled : R.string.update_not_scheduled);
        renderUpdateResult();
        home.status.setText(on ? R.string.status_on : starting ? R.string.status_starting : R.string.status_off);
        home.detail.setText(on ? R.string.nav_protection_hint_on
            : starting ? R.string.nav_protection_hint_starting : R.string.nav_protection_hint_off);
        home.protectionIcon.setImageResource(on ? tracewall.auth.R.drawable.ic_tw_shield
            : tracewall.auth.R.drawable.ic_tw_shield_outline);

        QueryLog.Stats s = QueryLog.get().stats();
        // Rule count comes from the live policy, not the service's boot-time
        // snapshot: allowing a host changes it without restarting the tunnel.
        java.text.NumberFormat numbers = java.text.NumberFormat.getIntegerInstance();
        home.statQueries.setText(numbers.format(s.total));
        home.statBlocked.setText(numbers.format(s.blocked));
        home.statRules.setText(numbers.format(BlocklistStore.get().size()));

        List<QueryLog.Entry> recent = QueryLog.get().snapshot(500);
        List<QueryLog.Entry> visible = new java.util.ArrayList<>();
        String filter = feed.queryFilter.getText().toString();
        for (QueryLog.Entry entry : recent) {
            if (tracewall.data.TextFilter.matches(filter, entry.host, labels.label(entry.uid))) visible.add(entry);
            if (visible.size() == 60) break;
        }
        adapter.submit(visible);
        if (pendingScroll != null && !visible.isEmpty()) {
            feed.list.getLayoutManager().onRestoreInstanceState(pendingScroll);
            pendingScroll = null;
        }
        boolean filtered = !filter.trim().isEmpty();
        boolean empty = visible.isEmpty();
        feed.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        feed.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        feed.clearSearch.setVisibility(filtered ? View.VISIBLE : View.GONE);
        feed.activityCount.setText(getString(R.string.nav_live_count, visible.size()));
        feed.emptyTitle.setText(filtered ? R.string.nav_empty_search_title
            : on ? R.string.nav_empty_on_title : R.string.nav_empty_title);
        feed.emptyBody.setText(filtered ? R.string.nav_empty_search
            : on ? R.string.nav_empty_on : R.string.nav_empty_off);
        feed.emptyAction.setVisibility(filtered || !on ? View.VISIBLE : View.GONE);
        feed.emptyAction.setText(filtered ? R.string.nav_clear_search : R.string.nav_open_home);
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
        home.trend.setSeries(allowed, blocked);
        int totalAllowed = 0, totalBlocked = 0;
        for (int count : allowed) totalAllowed += count;
        for (int count : blocked) totalBlocked += count;
        home.trendEmpty.setVisibility(totalAllowed + totalBlocked == 0 ? View.VISIBLE : View.GONE);
        home.trend.setContentDescription(getString(R.string.nav_chart_description, totalAllowed, totalBlocked));
    }
}
