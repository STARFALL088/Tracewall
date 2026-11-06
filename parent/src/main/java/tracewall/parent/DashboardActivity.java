package tracewall.parent;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.firestore.ListenerRegistration;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import tracewall.parent.databinding.ActivityDashboardBinding;
import tracewall.parent.databinding.ItemHistoryBinding;
import tracewall.parent.sync.FirebaseParent;

/**
 * Four focused destinations share one verified device session. Switching
 * destinations never creates extra Firebase subscriptions.
 *
 * The Firestore listener is attached in onStart and removed in onStop to
 * avoid leaking it when the activity is in the back stack.
 */
public class DashboardActivity extends AppCompatActivity {

    private static final int HISTORY_LIMIT = 50;
    private static final String STATE_PAGE = "parent_page";
    private static final String STATE_SCROLL = "parent_scroll";
    private static final String STATE_ADVANCED = "parent_advanced";
    private static final String START_PAGE = "parent_start_page";

    private ActivityDashboardBinding ui;
    private HistoryAdapter adapter;
    private ListenerRegistration historyReg;
    private ListenerRegistration eventsReg;
    private String latestEventId;
    private List<Map<String, Object>> recentHistory = Collections.emptyList();
    private List<Map<String, Object>> recentEvents = Collections.emptyList();
    private boolean historyLoaded, eventsLoaded, historyCached, eventsCached, historyFailed, eventsFailed;
    private boolean accessChecking, accessFailed, ruleLoadFailed;
    private int currentPage = R.id.nav_overview;
    private int[] scrollPositions = new int[4];
    private final boolean[] restoreScroll = new boolean[4];
    private OnBackPressedCallback pageBack;
    private final tracewall.blocklist.RuleLists rules = new tracewall.blocklist.RuleLists();
    private com.google.firebase.database.ValueEventListener rulesReg;
    private boolean renderingRules;
    private boolean writingRules;
    private String childUid;
    private boolean started;
    private int connection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        setSupportActionBar(ui.dashboardToolbar);
        ui.dashboardToolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        pageBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { ui.bottomNavigation.setSelectedItemId(R.id.nav_overview); }
        };
        getOnBackPressedDispatcher().addCallback(this, pageBack);
        // Selection and each destination's scroll offset are restored explicitly.
        ui.bottomNavigation.setSaveEnabled(false);
        for (NestedScrollView destination : pages()) destination.setSaveEnabled(false);
        // Only a current rules snapshot may set category state, never restored widget state.
        ui.catMalware.setSaveEnabled(false); ui.catGambling.setSaveEnabled(false);
        ui.catAdult.setSaveEnabled(false); ui.catAds.setSaveEnabled(false);
        ui.bottomNavigation.setOnItemSelectedListener(item -> { showPage(item.getItemId()); return true; });
        if (savedInstanceState != null) {
            int[] savedScroll = savedInstanceState.getIntArray(STATE_SCROLL);
            if (savedScroll != null && savedScroll.length == 4) scrollPositions = savedScroll;
            java.util.Arrays.fill(restoreScroll, true);
        }
        boolean advanced = savedInstanceState != null && savedInstanceState.getBoolean(STATE_ADVANCED);
        setAdvanced(advanced);
        ui.advancedToggle.setOnClickListener(v -> setAdvanced(ui.advancedRules.getVisibility() != View.VISIBLE));
        int page = savedInstanceState == null ? getIntent().getIntExtra(START_PAGE, R.id.nav_overview)
            : savedInstanceState.getInt(STATE_PAGE, R.id.nav_overview);
        if (ui.bottomNavigation.getMenu().findItem(page) == null) page = R.id.nav_overview;
        ui.bottomNavigation.setSelectedItemId(page);
        showPage(page);
        // Keep a usable editor viewport when the keyboard opens on a small phone.
        ViewCompat.setOnApplyWindowInsetsListener(ui.getRoot(), (view, insets) -> {
            boolean keyboard = insets.isVisible(WindowInsetsCompat.Type.ime());
            ui.bottomNavigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            ui.deviceSwitcher.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            return insets;
        });

        childUid = prefs().getString(PairingActivity.KEY_CHILD_UID, "");
        renderDevice();

        adapter = new HistoryAdapter();
        ui.historyList.setLayoutManager(new LinearLayoutManager(this));
        ui.historyList.setAdapter(adapter);
        ui.historyList.setNestedScrollingEnabled(true);
        ui.historyList.setItemAnimator(null);

        ui.deviceSwitcher.setOnClickListener(v -> pickDevice());
        ui.switchDeviceButton.setOnClickListener(v -> pickDevice());
        ui.addDeviceButton.setOnClickListener(v -> addDevice());
        ui.renameDeviceButton.setOnClickListener(v -> renameDevice());
        ui.forgetDeviceButton.setOnClickListener(v -> confirmUnpair());
        ui.forgetSavedButton.setOnClickListener(v -> forgetSavedDevice());
        ui.dashboardAccountButton.setOnClickListener(v -> openAccount());
        ui.openActivity.setOnClickListener(v -> ui.bottomNavigation.setSelectedItemId(R.id.nav_activity));
        ui.openRules.setOnClickListener(v -> ui.bottomNavigation.setSelectedItemId(R.id.nav_rules));
        ui.overviewApps.setOnClickListener(v -> openApps());
        ui.rulesApps.setOnClickListener(v -> openApps());
        ui.retryConnection.setOnClickListener(v -> refreshCloud());
        ui.activityRetry.setOnClickListener(v -> refreshCloud());
        ui.rulesRetry.setOnClickListener(v -> refreshCloud());

        ui.allowButton.setOnClickListener(v -> pushAllowRule());
        ui.blockUidButton.setOnClickListener(v -> pushBlockUidRule());

        ui.editRules.setOnClickListener(v -> removeRule());
        setRuleControls(false);

        wireCategory(ui.catMalware, "malware");
        wireCategory(ui.catGambling, "gambling");
        wireCategory(ui.catAdult, "adult");
        wireCategory(ui.catAds, "ads");
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        renderDevice();
        renderAccount();
        connectCloud();
    }

    private void refreshCloud() {
        if (!writingRules) connectCloud();
    }

    private void connectCloud() {
        if (!started) return;
        int ticket = ++connection;
        removeCloudListeners();
        // Never render one account's cached feed after sign-out/account selection.
        recentHistory = Collections.emptyList();
        recentEvents = Collections.emptyList();
        historyLoaded = eventsLoaded = historyCached = eventsCached = historyFailed = eventsFailed = false;
        accessChecking = true; accessFailed = ruleLoadFailed = false;
        latestEventId = null;
        renderFeed();
        ui.connectionStatus.setText(R.string.parent_access_checking);
        ui.tamperBanner.setVisibility(View.GONE);
        ui.lastSeen.setText(R.string.last_seen_unknown);
        rules.pause();
        setRuleControls(false);
        setRuleStatus(writingRules ? R.string.rules_pending : R.string.rules_waiting);
        if (FirebaseParent.get().uid() == null) {
            startActivity(new android.content.Intent(this, PairingActivity.class));
            finish();
            return;
        }
        FirebaseParent.get().verifyAccess(childUid, () -> attachCloud(ticket), () -> {
            if (!current(ticket)) return;
            accessChecking = false; accessFailed = true;
            ui.connectionStatus.setText(R.string.device_switch_failed);
            setRuleStatus(R.string.device_switch_failed);
            renderFeed();
        });
    }
    private boolean current(int ticket) { return started && ticket == connection && !isFinishing() && !isDestroyed(); }
    private void attachCloud(int ticket) {
        if (!current(ticket)) return;
        accessChecking = false;
        ui.connectionStatus.setText(R.string.parent_access_verified);
        renderFeed();
        rulesReg = FirebaseParent.get().listenRules(childUid, snapshot -> {
            if (!current(ticket)) return;
            try {
                tracewall.data.RemoteRules current = tracewall.data.RemoteRules.parse(snapshot.getValue());
                List<Integer> uids = new ArrayList<>();
                for (String uid : current.blockedUids) uids.add(Integer.parseInt(uid));
                rules.load(current.allow, uids);
                ruleLoadFailed = false;
                renderingRules = true;
                ui.catMalware.setChecked(current.categories == null || current.categories.contains("malware"));
                ui.catGambling.setChecked(current.categories == null || current.categories.contains("gambling"));
                ui.catAdult.setChecked(current.categories == null || current.categories.contains("adult"));
                ui.catAds.setChecked(current.categories == null || current.categories.contains("ads"));
                renderingRules = false;
                setRuleControls(true);
                if (!writingRules) setRuleStatus(R.string.rules_ready);
            } catch (IllegalArgumentException e) {
                rules.pause();
                ruleLoadFailed = true;
                setRuleControls(false);
                setRuleStatus(R.string.rules_failed);
            }
        }, () -> { if (current(ticket)) { rules.pause(); ruleLoadFailed = true; setRuleControls(false); setRuleStatus(R.string.rules_failed); } });
        historyReg = FirebaseParent.get().listenHistory(
            childUid,
            HISTORY_LIMIT,
            new FirebaseParent.HistoryListener() {
                @Override public void onSnapshotState(boolean cached) { if (current(ticket)) historyCached = cached; }
                @Override public void onError() {
                    if (!current(ticket)) return;
                    historyFailed = true; renderFeed();
                }
                @Override public void onHistory(List<Map<String, Object>> records) {
                    if (!current(ticket)) return;
                    historyLoaded = true; historyFailed = false;
                    recentHistory = records;
                    renderFeed();
                    if (!records.isEmpty()) {
                        ui.lastSeen.setText(getString(R.string.last_seen,
                            formatTime(records.get(0).get("time_ms"))));
                    } else ui.lastSeen.setText(R.string.last_seen_unknown);
                }
            }
        );
        eventsReg = FirebaseParent.get().listenEvents(childUid, new FirebaseParent.HistoryListener() {
            @Override public void onSnapshotState(boolean cached) { if (current(ticket)) eventsCached = cached; }
            @Override public void onError() {
                if (!current(ticket)) return;
                eventsFailed = true; renderFeed();
            }
            @Override public void onHistory(List<Map<String, Object>> events) {
            if (!current(ticket)) return;
            eventsLoaded = true; eventsFailed = false;
            ui.tamperBanner.setVisibility(View.GONE);
            recentEvents = events;
            renderFeed();
            Map<String, Object> event = null;
            for (Map<String, Object> row : events) {
                if ("protection_off".equals(row.get("type"))) { event = row; break; }
            }
            if (event == null) return;
            latestEventId = String.valueOf(event.get("id"));
            if (latestEventId.equals(prefs().getString("dismissed_event_" + childUid, ""))) return;
            ui.tamperText.setText(getString(R.string.protection_off_event,
                String.valueOf(event.get("reason")), formatTime(event.get("time_ms"))));
            ui.tamperBanner.setVisibility(View.VISIBLE);
            }
        });
        ui.dismissTamper.setOnClickListener(v -> {
            if (latestEventId != null) prefs().edit()
                .putString("dismissed_event_" + childUid, latestEventId).apply();
            ui.tamperBanner.setVisibility(View.GONE);
        });
    }

    private static String formatTime(Object value) {
        long time = value instanceof Number ? ((Number) value).longValue() : 0;
        return java.text.DateFormat.getDateTimeInstance().format(new Date(time));
    }

    private NestedScrollView[] pages() {
        return new NestedScrollView[]{ui.overviewPage, ui.activityPage, ui.rulesPage, ui.devicesPage};
    }

    private int pageIndex(int page) {
        if (page == R.id.nav_activity) return 1;
        if (page == R.id.nav_rules) return 2;
        if (page == R.id.nav_devices) return 3;
        return 0;
    }

    private void showPage(int page) {
        NestedScrollView[] destinations = pages();
        int previous = pageIndex(currentPage);
        if (!restoreScroll[previous]) scrollPositions[previous] = destinations[previous].getScrollY();
        if (page != currentPage) {
            View focused = getCurrentFocus();
            if (focused != null) {
                android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                focused.clearFocus();
            }
        }
        currentPage = page;
        int selected = pageIndex(page);
        for (int i = 0; i < destinations.length; i++) destinations[i].setVisibility(i == selected ? View.VISIBLE : View.GONE);
        int title = selected == 1 ? R.string.parent_nav_activity : selected == 2 ? R.string.parent_nav_rules
            : selected == 3 ? R.string.parent_nav_devices : R.string.parent_nav_overview;
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(title);
        pageBack.setEnabled(selected != 0);
        destinations[selected].post(() -> restorePageScroll(selected));
    }

    private void restorePageScroll(int index) {
        if (isFinishing() || isDestroyed() || index != pageIndex(currentPage)) return;
        if (restoreScroll[index]) {
            // Do not consume a restored offset while asynchronous content is still empty.
            if (index == 1 && !accessFailed && !historyFailed && !eventsFailed
                    && (!historyLoaded || !eventsLoaded
                        || (adapter.getItemCount() == 0 && (historyCached || eventsCached)))) return;
            if (index == 2 && !rules.isReady() && !ruleLoadFailed && !accessFailed) return;
            restoreScroll[index] = false;
        }
        pages()[index].scrollTo(0, scrollPositions[index]);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        NestedScrollView[] destinations = pages();
        for (int i = 0; i < destinations.length; i++)
            if (!restoreScroll[i]) scrollPositions[i] = destinations[i].getScrollY();
        state.putInt(STATE_PAGE, currentPage);
        state.putIntArray(STATE_SCROLL, scrollPositions);
        state.putBoolean(STATE_ADVANCED, ui.advancedRules.getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    private void setAdvanced(boolean expanded) {
        ui.advancedRules.setVisibility(expanded ? View.VISIBLE : View.GONE);
        ViewCompat.setStateDescription(ui.advancedToggle,
            getString(expanded ? R.string.parent_expanded : R.string.parent_collapsed));
    }

    private void renderDevice() {
        String name = ParentDevices.name(this, childUid);
        ui.overviewDeviceName.setText(name);
        ui.deviceSwitcher.setText(getString(R.string.parent_device_switch, name));
        ui.childUidLabel.setText(getString(R.string.named_child_label, name, childUid));
        int count = ParentDevices.all(this).names().size();
        ui.savedDeviceSummary.setText(count == 0 ? getString(R.string.parent_devices_empty)
            : getString(R.string.parent_devices_saved, count));
    }

    private void renderAccount() {
        com.google.firebase.auth.FirebaseUser account = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        ui.dashboardAccountStatus.setText(account == null || FirebaseParent.get().uid() == null ? R.string.account_local
            : account.isAnonymous() ? R.string.account_guest : R.string.account_linked);
    }

    private void pickDevice() { DevicePicker.show(this, this::restartDashboard, this::addDevice); }
    private void openApps() { startActivity(new android.content.Intent(this, AppPickerActivity.class)); }
    private void openAccount() {
        startActivity(new android.content.Intent(this, tracewall.auth.AuthActivity.class)
            .putExtra(tracewall.auth.AuthActivity.EXTRA_ROLE, tracewall.auth.AuthActivity.ROLE_PARENT)
            .putExtra(tracewall.auth.AuthActivity.EXTRA_BOUND_UID, tracewall.auth.IdentityAnchor.get(this)));
    }

    private void renderFeed() {
        adapter.submit(tracewall.data.ActivityFeed.merge(recentHistory, recentEvents, 100));
        boolean loading = !accessFailed && !historyFailed && !eventsFailed
            && (accessChecking || !historyLoaded || !eventsLoaded);
        int message;
        int title;
        if (accessFailed) {
            message = R.string.device_switch_failed; title = R.string.parent_feed_failed_title;
        } else if (historyFailed || eventsFailed) {
            message = R.string.parent_feed_failed; title = R.string.parent_feed_failed_title;
        } else if (accessChecking) {
            message = R.string.parent_access_checking; title = R.string.parent_feed_loading_title;
        } else if (historyCached || eventsCached) {
            message = R.string.parent_feed_cached; title = R.string.parent_feed_loading_title;
        } else if (loading) {
            message = adapter.getItemCount() == 0 ? R.string.parent_feed_loading : R.string.parent_feed_partial;
            title = R.string.parent_feed_loading_title;
        } else {
            message = adapter.getItemCount() == 0 ? R.string.parent_feed_empty : R.string.parent_feed_ready;
            title = R.string.parent_feed_empty_title;
        }
        ui.feedProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        ui.feedStatus.setText(message);
        ui.overviewActivityStatus.setText(message);
        ui.feedEmptyTitle.setText(title);
        ui.feedEmptyBody.setText(message);
        ui.feedEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        ui.activityPage.post(() -> { if (restoreScroll[1]) restorePageScroll(1); });
    }

    private void setRuleStatus(int text) {
        ui.ruleStatus.setText(text);
        ui.overviewRuleStatus.setText(text);
        ui.rulesProgress.setVisibility(text == R.string.rules_waiting || text == R.string.rules_pending ? View.VISIBLE : View.GONE);
        if (rules.isReady()) ui.overviewRulesSummary.setText(getString(R.string.parent_rule_summary,
            rules.allows().size(), rules.blockedUids().size()));
        else ui.overviewRulesSummary.setText(R.string.parent_rule_summary_waiting);
        boolean canRefresh = started && !writingRules;
        ui.retryConnection.setEnabled(canRefresh);
        ui.activityRetry.setEnabled(canRefresh);
        ui.rulesRetry.setEnabled(canRefresh);
        ui.rulesPage.post(() -> { if (restoreScroll[2]) restorePageScroll(2); });
    }

    @Override
    protected void onStop() {
        NestedScrollView[] destinations = pages();
        for (int i = 0; i < destinations.length; i++)
            if (!restoreScroll[i]) scrollPositions[i] = destinations[i].getScrollY();
        java.util.Arrays.fill(restoreScroll, true);
        started = false; connection++;
        super.onStop();
        rules.pause();
        setRuleControls(false);
        removeCloudListeners();
    }

    private void removeCloudListeners() {
        if (rulesReg != null) {
            FirebaseParent.get().removeRulesListener(childUid, rulesReg);
            rulesReg = null;
        }
        if (eventsReg != null) { eventsReg.remove(); eventsReg = null; }
        if (historyReg != null) {
            historyReg.remove();
            historyReg = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 1, Menu.NONE, R.string.menu_forget_current);
        menu.add(Menu.NONE, 2, Menu.NONE, R.string.menu_devices);
        menu.add(Menu.NONE, 3, Menu.NONE, R.string.menu_app_picker);
        menu.add(Menu.NONE, 4, Menu.NONE, R.string.menu_rename_device);
        menu.add(Menu.NONE, 5, Menu.NONE, R.string.menu_forget_device);
        menu.add(Menu.NONE, 6, Menu.NONE, R.string.parent_account_button)
            .setIcon(tracewall.auth.R.drawable.ic_tw_account).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 6) {
            openAccount();
            return true;
        }
        if (item.getItemId() == 2) { pickDevice(); return true; }
        if (item.getItemId() == 3) { openApps(); return true; }
        if (item.getItemId() == 4) { renameDevice(); return true; }
        if (item.getItemId() == 5) { forgetSavedDevice(); return true; }
        if (item.getItemId() == 1) {
            confirmUnpair();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ----------------------------------------------------------------- rules

    private void setRuleControls(boolean enabled) {
        enabled = enabled && started && !writingRules;
        ui.categoryControls.setVisibility(rules.isReady() && started ? View.VISIBLE : View.GONE);
        ui.allowButton.setEnabled(enabled);
        ui.blockUidButton.setEnabled(enabled);
        ui.editRules.setEnabled(enabled);
        ui.catMalware.setEnabled(enabled);
        ui.catGambling.setEnabled(enabled);
        ui.catAdult.setEnabled(enabled);
        ui.catAds.setEnabled(enabled);
    }

    private FirebaseParent.WriteListener beginWrite() {
        int ticket = connection;
        String writerUid = FirebaseParent.get().uid();
        writingRules = true;
        setRuleControls(false);
        setRuleStatus(R.string.rules_pending);
        return accepted -> {
            writingRules = false;
            if (!started || isFinishing() || isDestroyed()
                    || !java.util.Objects.equals(writerUid, FirebaseParent.get().uid())) return;
            setRuleControls(rules.isReady());
            // A late acknowledgment must not describe a different listener session as saved.
            if (current(ticket)) setRuleStatus(accepted ? R.string.rules_saved : R.string.rules_failed);
            else setRuleStatus(rules.isReady() ? R.string.rules_ready
                : ruleLoadFailed ? R.string.rules_failed : R.string.rules_waiting);
        };
    }

    private void wireCategory(CompoundButton toggle, String category) {
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (renderingRules || !rules.isReady() || writingRules) return;
            FirebaseParent.get().editCategory(childUid, category, checked, beginWrite());
        });
    }

    private void removeRule() {
        if (!rules.isReady()) return;
        List<String> allows = rules.allows();
        List<Integer> uids = rules.blockedUids();
        List<String> rows = new ArrayList<>();
        for (String host : allows) rows.add(getString(R.string.allow_entry, host));
        for (int uid : uids) rows.add(getString(R.string.uid_entry, uid));
        if (rows.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.remove_rule).setMessage(R.string.parent_no_rules)
                .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.remove_rule)
            .setItems(rows.toArray(new String[0]), (d, which) -> {
                if (!rules.isReady() || writingRules) return;
                if (which < allows.size()) FirebaseParent.get().editAllow(childUid, allows.get(which), false, beginWrite());
                else FirebaseParent.get().editBlockedUid(childUid, uids.get(which - allows.size()), false, beginWrite());
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pushAllowRule() {
        if (!rules.isReady()) return;
        String domain;
        try { domain = tracewall.data.RuleEdits.domain(ui.allowInput.getText().toString()); }
        catch (IllegalArgumentException e) {
            ui.allowInput.setError(getString(R.string.rule_domain_invalid));
            return;
        }
        FirebaseParent.get().editAllow(childUid, domain, true, beginWrite());
        ui.allowInput.setText("");
    }

    private void pushBlockUidRule() {
        if (!rules.isReady()) return;
        String raw = ui.blockUidInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            ui.blockUidInput.setError(getString(R.string.rule_uid_empty));
            return;
        }
        int uid;
        try {
            uid = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            ui.blockUidInput.setError(getString(R.string.rule_uid_invalid));
            return;
        }
        if (uid < 0) {
            ui.blockUidInput.setError(getString(R.string.rule_uid_invalid));
            return;
        }
        FirebaseParent.get().editBlockedUid(childUid, uid, true, beginWrite());
        ui.blockUidInput.setText("");
    }

    // --------------------------------------------------------------- unpair

    private void confirmUnpair() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.unpair_title)
            .setMessage(R.string.device_forget_note)
            .setPositiveButton(R.string.unpair_yes, (d, w) -> unpair())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void unpair() {
        ParentDevices.forget(this, childUid);
        addDevice();
    }
    private void addDevice() {
        startActivity(new android.content.Intent(this, PairingActivity.class).putExtra("add_device", true));
        finish();
    }
    private void restartDashboard() {
        startActivity(new android.content.Intent(this, DashboardActivity.class).putExtra(START_PAGE, currentPage)); finish();
    }
    private void renameDevice() {
        android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true); name.setText(ParentDevices.name(this, childUid));
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this).setTitle(R.string.menu_rename_device).setView(name)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                try {
                    ParentDevices.remember(this, childUid, name.getText().toString());
                    renderDevice();
                } catch (IllegalArgumentException invalid) { Toast.makeText(this, R.string.device_name_invalid, Toast.LENGTH_LONG).show(); }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }
    private void forgetSavedDevice() {
        java.util.Map<String, String> names = ParentDevices.all(this).names();
        List<String> uids = new ArrayList<>(names.keySet());
        List<String> labels = new ArrayList<>();
        for (String uid : uids) labels.add(getString(R.string.named_child_label, names.get(uid), uid));
        new AlertDialog.Builder(this).setTitle(R.string.menu_forget_device)
            .setItems(labels.toArray(new String[0]), (list, index) -> new AlertDialog.Builder(this)
                .setMessage(R.string.device_forget_note).setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    ParentDevices.forget(this, uids.get(index));
                    if (childUid.equals(uids.get(index))) addDevice();
                    else renderDevice();
                }).setNegativeButton(android.R.string.cancel, null).show())
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PairingActivity.PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------- history list

    /** Adapter for the Firestore history rows. */
    private static final class HistoryAdapter
        extends RecyclerView.Adapter<HistoryAdapter.VH>
    {

        private List<Map<String, Object>> items = Collections.emptyList();

        void submit(List<Map<String, Object>> data) {
            items = data != null ? data : Collections.emptyList();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(
                ItemHistoryBinding.inflate(
                    LayoutInflater.from(parent.getContext()),
                    parent,
                    false
                )
            );
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Map<String, Object> r = items.get(position);

            Object timeObj = r.get("time_ms");
            long timeMs =
                timeObj instanceof Number ? ((Number) timeObj).longValue() : 0L;
            String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(
                new Date(timeMs)
            );
            h.b.verdict.setTextColor(ContextCompat.getColor(h.itemView.getContext(), tracewall.auth.R.color.tw_muted));

            if ("new_app".equals(r.get("type"))) {
                h.b.time.setText(time);
                h.b.host.setText(h.itemView.getContext().getString(R.string.new_app_row, String.valueOf(r.get("label"))));
                h.b.verdict.setText(R.string.event_installed);
                h.b.policy.setText(String.valueOf(r.get("package")));
                return;
            }
            if ("protection_off".equals(r.get("type"))) {
                h.b.time.setText(time);
                h.b.host.setText(R.string.event_protection_off);
                h.b.verdict.setText(R.string.event_device);
                h.b.policy.setText(String.valueOf(r.get("reason")));
                return;
            }
            String host = String.valueOf(r.getOrDefault("host", "?"));
            boolean blocked = Boolean.TRUE.equals(r.get("blocked"));
            String policy = String.valueOf(r.getOrDefault("policy", ""));

            h.b.time.setText(time);
            h.b.host.setText(host);
            h.b.verdict.setText(blocked ? R.string.verdict_block : R.string.verdict_allow);
            h.b.verdict.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                blocked ? tracewall.auth.R.color.tw_danger : tracewall.auth.R.color.tw_success));
            h.b.policy.setText(policy);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class VH extends RecyclerView.ViewHolder {

            final ItemHistoryBinding b;

            VH(ItemHistoryBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}
