package tracewall.child.ui;

import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import tracewall.blocklist.ScheduledProfile;
import tracewall.blocklist.TimedAllow;
import tracewall.blocklist.WeeklySchedule;
import tracewall.child.R;
import tracewall.child.block.BlocklistStore;
import tracewall.child.databinding.ActivityRulesBinding;
import tracewall.child.io.RulesTransfer;
import tracewall.data.LocalRuleEdits;
import tracewall.data.RulesJson;

/** Local DNS editor. Every durable mutation and backup passes PinGate, not merely screen entry. */
public final class RulesActivity extends AppCompatActivity {
    /** Navigation only. Opening a destination never authorizes a saved change. */
    public static final String EXTRA_PAGE = "rules_page";
    public static final int PAGE_DOMAINS = 0;
    public static final int PAGE_SCHEDULES = 1;
    public static final int PAGE_BACKUP = 2;
    private static final String STATE_PAGE = "rules_active_page";
    private static final String STATE_SCROLLS = "rules_page_scrolls";
    private static final ExecutorService IO = tracewall.net.BoundedWorkPool.create("tracewall-rules-ui", 1, 16);
    private ActivityRulesBinding ui;
    private ScrollView[] pages;
    private final int[] scrollPositions = new int[3];
    private int selectedPage = -1;
    private RulesJson snapshot;
    private CheckBox[] days;
    private Map<String, CheckBox> categoryChecks;
    private String editingName;
    private int startMinute = 18 * 60, endMinute = 21 * 60;
    private boolean saving;
    private boolean reading;
    private int operation;

    private final ActivityResultLauncher<String[]> document = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                // The picker may have outlived this Activity or an earlier authorization.
                if (uri == null) return;
                PinGate.require(this, () -> new AlertDialog.Builder(this)
                        .setTitle(R.string.rules9_import).setMessage(R.string.rules9_import_confirm)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            if (saving || !alive()) return;
                            operation++;
                            setSaving(true);
                            RulesTransfer.importRules(this, uri,
                                () -> { setSaving(false); newProfile(); refresh(); },
                                () -> { setSaving(false); refresh(); });
                        })
                        .setNegativeButton(android.R.string.cancel, null).show());
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityRulesBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        pages = new ScrollView[] {ui.rulesScroll, ui.schedulesScroll, ui.backupScroll};
        for (View heading : new View[] {ui.rulesTitle, ui.domainsHeading, ui.temporaryHeading,
                ui.savedDomainsHeading, ui.profilesHeading, ui.savedSchedulesHeading, ui.profileHeading,
                ui.whenHeading, ui.blocksHeading, ui.uidsHeading, ui.backupHeading, ui.exportHeading,
                ui.importHeading, ui.backupDetailsHeading, ui.scopeHeading})
            ViewCompat.setAccessibilityHeading(heading, true);
        ViewCompat.setAccessibilityPaneTitle(ui.rulesScroll, getString(R.string.rulesnav_domains));
        ViewCompat.setAccessibilityPaneTitle(ui.schedulesScroll, getString(R.string.rulesnav_schedules));
        ViewCompat.setAccessibilityPaneTitle(ui.backupScroll, getString(R.string.rulesnav_backups));
        days = new CheckBox[] {ui.dayMonday, ui.dayTuesday, ui.dayWednesday, ui.dayThursday,
                ui.dayFriday, ui.daySaturday, ui.daySunday};
        categoryChecks = new LinkedHashMap<>();
        categoryChecks.put("ads", ui.profileAds);
        categoryChecks.put("adult", ui.profileAdult);
        categoryChecks.put("gambling", ui.profileGambling);
        categoryChecks.put("malware", ui.profileMalware);
        ui.rulesBack.setNavigationOnClickListener(v -> finish());
        ui.rulesTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPage(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        ui.refreshRules.setOnClickListener(v -> refresh());
        ui.addDeny.setOnClickListener(v -> addDomain(false, false));
        ui.addTimed.setOnClickListener(v -> addDomain(true, false));
        ui.addPermanent.setOnClickListener(v -> addDomain(false, true));
        ui.showDenies.setOnClickListener(v -> showPatterns(0));
        ui.showAllows.setOnClickListener(v -> showPatterns(1));
        ui.showTimed.setOnClickListener(v -> showPatterns(2));
        ui.newProfile.setOnClickListener(v -> { newProfile(); scrollToEditor(); });
        ui.profileStart.setOnClickListener(v -> chooseTime(true));
        ui.profileEnd.setOnClickListener(v -> chooseTime(false));
        ui.saveProfile.setOnClickListener(v -> saveProfile());
        ui.deleteProfile.setOnClickListener(v -> deleteProfile());
        ui.exportRules.setOnClickListener(v -> PinGate.require(this, () -> RulesTransfer.exportRules(this)));
        ui.importRules.setOnClickListener(v -> PinGate.require(this, this::chooseImport));
        ui.domainHelpToggle.setOnClickListener(v -> setDomainHelp(ui.domainHelp.getVisibility() != View.VISIBLE));
        ui.scheduleHelpToggle.setOnClickListener(v -> setScheduleHelp(ui.scheduleHelp.getVisibility() != View.VISIBLE));
        if (state == null) {
            ui.ruleMinutes.setText(java.text.NumberFormat.getIntegerInstance().format(15));
            newProfile();
        } else {
            editingName = state.getString("profile_original");
            startMinute = state.getInt("profile_start", startMinute);
            endMinute = state.getInt("profile_end", endMinute);
            int[] restoredScrolls = state.getIntArray(STATE_SCROLLS);
            if (restoredScrolls != null && restoredScrolls.length == scrollPositions.length)
                System.arraycopy(restoredScrolls, 0, scrollPositions, 0, scrollPositions.length);
            editorHeading();
        }
        setDomainHelp(state != null && state.getBoolean("rules_domain_help"));
        setScheduleHelp(state != null && state.getBoolean("rules_schedule_help"));
        selectPage(state == null ? getIntent().getIntExtra(EXTRA_PAGE, PAGE_DOMAINS)
                : state.getInt(STATE_PAGE, PAGE_DOMAINS));
        PinGate.ensureSet(this);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectPage(intent.getIntExtra(EXTRA_PAGE, PAGE_DOMAINS));
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        // Restore hierarchy drafts first, then page/scroll state. Hidden pages stay inflated.
        editorHeading();
        selectPage(state.getInt(STATE_PAGE, PAGE_DOMAINS));
        restoreScroll(selectedPage);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("profile_original", editingName);
        state.putInt("profile_start", startMinute);
        state.putInt("profile_end", endMinute);
        scrollPositions[selectedPage] = pages[selectedPage].getScrollY();
        state.putInt(STATE_PAGE, selectedPage);
        state.putIntArray(STATE_SCROLLS, scrollPositions.clone());
        state.putBoolean("rules_domain_help", ui.domainHelp.getVisibility() == View.VISIBLE);
        state.putBoolean("rules_schedule_help", ui.scheduleHelp.getVisibility() == View.VISIBLE);
        // Stable XML IDs let Android restore text and all checkbox drafts, without saving rules.
        super.onSaveInstanceState(state);
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    private void selectPage(int requested) {
        int page = requested >= PAGE_DOMAINS && requested <= PAGE_BACKUP ? requested : PAGE_DOMAINS;
        TabLayout.Tab tab = ui.rulesTabs.getTabAt(page);
        if (tab != null && !tab.isSelected()) tab.select();
        else if (selectedPage != page) showPage(page);
    }

    private void showPage(int page) {
        if (page < PAGE_DOMAINS || page > PAGE_BACKUP) return;
        if (selectedPage >= PAGE_DOMAINS && selectedPage != page) {
            scrollPositions[selectedPage] = pages[selectedPage].getScrollY();
            View focused = getCurrentFocus();
            if (focused != null) {
                focused.clearFocus();
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
        selectedPage = page;
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
        int title = page == PAGE_DOMAINS ? R.string.rulesnav_domains
                : page == PAGE_SCHEDULES ? R.string.rulesnav_schedules : R.string.rulesnav_backups;
        setTitle(title);
        restoreScroll(page);
    }

    private void restoreScroll(int page) {
        // A hidden ScrollView has not been measured; restore it only after it becomes visible.
        pages[page].post(() -> {
            if (alive() && selectedPage == page) pages[page].scrollTo(0, scrollPositions[page]);
        });
    }

    private void setDomainHelp(boolean expanded) {
        ui.domainHelp.setVisibility(expanded ? View.VISIBLE : View.GONE);
        ui.domainHelpToggle.setText(expanded ? R.string.rulesnav_hide_domain_details : R.string.rulesnav_show_domain_details);
        ViewCompat.setStateDescription(ui.domainHelpToggle, getString(expanded ? R.string.rulesnav_expanded : R.string.rulesnav_collapsed));
    }

    private void setScheduleHelp(boolean expanded) {
        ui.scheduleHelp.setVisibility(expanded ? View.VISIBLE : View.GONE);
        ui.scheduleHelpToggle.setText(expanded ? R.string.rulesnav_hide_schedule_details : R.string.rulesnav_show_schedule_details);
        ViewCompat.setStateDescription(ui.scheduleHelpToggle, getString(expanded ? R.string.rulesnav_expanded : R.string.rulesnav_collapsed));
    }

    private void scrollToEditor() {
        selectPage(PAGE_SCHEDULES);
        ui.schedulesScroll.post(() -> {
            if (!alive() || selectedPage != PAGE_SCHEDULES) return;
            Rect bounds = new Rect();
            ui.profileHeading.getDrawingRect(bounds);
            ui.schedulesContent.offsetDescendantRectToMyCoords(ui.profileHeading, bounds);
            scrollPositions[PAGE_SCHEDULES] = bounds.top;
            ui.schedulesScroll.smoothScrollTo(0, bounds.top);
        });
    }

    private void refresh() {
        if (saving || reading) return;
        reading = true;
        int ticket = operation;
        ui.rulesStatus.setText(R.string.rules9_loading);
        try { IO.execute(() -> {
            try {
                RulesJson saved = BlocklistStore.get().exportLocal(getApplicationContext());
                runOnUiThread(() -> {
                    reading = false;
                    if (!alive()) return;
                    if (saving || ticket != operation) { if (!saving) refresh(); return; }
                    snapshot = saved;
                    renderSnapshot();
                    ui.rulesStatus.setText(R.string.rulesnav_ready);
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    reading = false;
                    if (alive() && !saving && ticket == operation) ui.rulesStatus.setText(R.string.rules9_read_failed);
                });
            }
        }); } catch (RejectedExecutionException overloaded) {
            reading = false; error(R.string.rules9_read_failed);
        }
    }

    /** Invoked only inside a successful per-action PIN callback. Never cache an unlocked flag. */
    private void mutate(Runnable change, Runnable after) {
        if (saving || !alive()) return;
        operation++;
        setSaving(true);
        ui.rulesStatus.setText(R.string.rules9_saving);
        try { IO.execute(() -> {
            try {
                change.run();
                RulesJson saved = BlocklistStore.get().exportLocal(getApplicationContext());
                runOnUiThread(() -> {
                    if (!alive()) return;
                    snapshot = saved;
                    setSaving(false);
                    after.run();
                    renderSnapshot();
                    ui.rulesStatus.setText(R.string.rules9_saved);
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    setSaving(false);
                    error(R.string.rules9_edit_failed);
                });
            }
        }); } catch (RejectedExecutionException overloaded) {
            setSaving(false); error(R.string.rules9_edit_failed);
        }
    }

    private void setSaving(boolean value) {
        saving = value;
        for (Button button : new Button[] {ui.addDeny, ui.addTimed, ui.addPermanent, ui.saveProfile,
                ui.newProfile, ui.refreshRules, ui.importRules, ui.exportRules, ui.showDenies, ui.showAllows,
                ui.showTimed, ui.profileStart, ui.profileEnd})
            button.setEnabled(!value);
        for (android.view.View field : new android.view.View[] {ui.rulePatternLayout, ui.ruleMinutesLayout,
                ui.rulePattern, ui.ruleMinutes, ui.profileName,
                ui.profileUids, ui.profileEnabled}) field.setEnabled(!value);
        for (CheckBox day : days) day.setEnabled(!value);
        for (CheckBox category : categoryChecks.values()) category.setEnabled(!value);
        ui.deleteProfile.setEnabled(!value && editingName != null);
        for (int i = 0; i < ui.profileList.getChildCount(); i++) ui.profileList.getChildAt(i).setEnabled(!value);
    }

    private void addDomain(boolean temporary, boolean permanent) {
        final String pattern;
        try { pattern = RulesJson.normalizePattern(ui.rulePattern.getText().toString()); }
        catch (IllegalArgumentException e) { ui.rulePattern.setError(getString(R.string.rules9_domain_error)); return; }
        int minutes = 0;
        if (temporary) {
            try {
                minutes = Integer.parseInt(ui.ruleMinutes.getText().toString().trim());
                if (minutes < 1 || minutes > TimedAllow.MAX_DURATION_MINUTES) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { ui.ruleMinutes.setError(getString(R.string.rules9_duration_error)); return; }
        }
        final int duration = minutes;
        PinGate.require(this, () -> mutate(() -> {
            if (temporary) BlocklistStore.get().allowForMinutes(this, pattern, duration, System.currentTimeMillis());
            else if (permanent) {
                if (!BlocklistStore.get().allow(this, pattern)) throw new IllegalArgumentException("Allow rejected");
            }
            else BlocklistStore.get().setDeny(this, pattern, true);
        }, () -> {}));
    }

    private void showPatterns(int kind) {
        if (snapshot == null) return;
        List<String> patterns = new ArrayList<>(kind == 0 ? snapshot.deny : kind == 1 ? snapshot.allow : snapshot.temporaryAllows.keySet());
        int title = kind == 0 ? R.string.rules9_denies : kind == 1 ? R.string.rules9_allows : R.string.rules9_timed;
        if (patterns.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(title).setMessage(R.string.rules9_empty)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] labels = patterns.toArray(new String[0]);
        if (kind == 2) {
            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            long now = System.currentTimeMillis();
            for (int i = 0; i < labels.length; i++) {
                long expires = snapshot.temporaryAllows.get(patterns.get(i));
                labels[i] = getString(R.string.rules9_timed_row, patterns.get(i),
                        getString(expires <= now ? R.string.rules9_expired : R.string.rules9_until), format.format(new Date(expires)));
            }
        }
        new AlertDialog.Builder(this).setTitle(title).setItems(labels, (d, which) -> {
            String pattern = patterns.get(which);
            new AlertDialog.Builder(this).setTitle(R.string.rules9_remove)
                    .setMessage(getString(R.string.rules9_remove_confirm, pattern))
                    .setPositiveButton(android.R.string.ok, (confirm, w) -> PinGate.require(this, () -> mutate(() -> {
                        if (kind == 0) BlocklistStore.get().setDeny(this, pattern, false);
                        else if (kind == 1) BlocklistStore.get().removeAllow(this, pattern);
                        else BlocklistStore.get().removeTemporaryAllow(this, pattern);
                    }, () -> {})))
                    .setNegativeButton(android.R.string.cancel, null).show();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void renderSnapshot() {
        ui.showDenies.setText(getString(R.string.rulesnav_denies_count, snapshot.deny.size()));
        ui.showAllows.setText(getString(R.string.rulesnav_allows_count, snapshot.allow.size()));
        ui.showTimed.setText(getString(R.string.rulesnav_timed_count, snapshot.temporaryAllows.size()));
        ui.profileList.removeAllViews();
        ui.profilesEmpty.setVisibility(snapshot.profiles.isEmpty() ? View.VISIBLE : View.GONE);
        long now = System.currentTimeMillis();
        for (ScheduledProfile profile : snapshot.profiles) {
            int status = !profile.schedule.enabled ? R.string.rules9_disabled
                    : profile.schedule.isActive(now, TimeZone.getDefault()) ? R.string.rules9_active : R.string.rules9_inactive;
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < days.length; i++) if ((profile.schedule.days & (1 << i)) != 0) selected.add(days[i].getText().toString());
            MaterialButton edit = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            edit.setAllCaps(false);
            edit.setEnabled(!saving);
            edit.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            edit.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            edit.setIconResource(tracewall.auth.R.drawable.ic_tw_schedule);
            int gap = Math.round(8 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            row.topMargin = gap;
            edit.setLayoutParams(row);
            edit.setText(getString(R.string.rules9_profile_row, profile.name, android.text.TextUtils.join(", ", selected),
                    time(profile.schedule.startMinute), time(profile.schedule.endMinute), getString(status),
                    profile.categories.size(), profile.blockedUids.size()));
            edit.setOnClickListener(v -> editProfile(profile));
            ui.profileList.addView(edit);
        }
    }

    private void newProfile() {
        editingName = null;
        ui.profileName.setText("");
        ui.profileEnabled.setChecked(true);
        for (CheckBox day : days) day.setChecked(true);
        for (Map.Entry<String, CheckBox> entry : categoryChecks.entrySet()) entry.getValue().setChecked(entry.getKey().equals("ads"));
        ui.profileUids.setText("");
        startMinute = 18 * 60; endMinute = 21 * 60;
        editorHeading();
    }

    private void editProfile(ScheduledProfile profile) {
        editingName = profile.name;
        ui.profileName.setText(profile.name);
        ui.profileEnabled.setChecked(profile.schedule.enabled);
        for (int i = 0; i < days.length; i++) days[i].setChecked((profile.schedule.days & (1 << i)) != 0);
        for (Map.Entry<String, CheckBox> entry : categoryChecks.entrySet()) entry.getValue().setChecked(profile.categories.contains(entry.getKey()));
        List<String> uids = new ArrayList<>();
        for (int uid : profile.blockedUids) uids.add(Integer.toString(uid));
        ui.profileUids.setText(android.text.TextUtils.join(", ", uids));
        startMinute = profile.schedule.startMinute; endMinute = profile.schedule.endMinute;
        editorHeading();
        scrollToEditor();
    }

    private void editorHeading() {
        ui.profileHeading.setText(editingName == null ? getString(R.string.rules9_new_heading) : getString(R.string.rules9_edit_heading, editingName));
        ui.profileStart.setText(getString(R.string.rules9_start, time(startMinute)));
        ui.profileEnd.setText(getString(R.string.rules9_end, time(endMinute)));
        ui.deleteProfile.setEnabled(!saving && editingName != null);
    }

    private String time(int minute) { return String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60); }

    private void chooseTime(boolean start) {
        int minute = start ? startMinute : endMinute;
        new TimePickerDialog(this, (view, hour, part) -> {
            if (start) startMinute = hour * 60 + part; else endMinute = hour * 60 + part;
            editorHeading();
        }, minute / 60, minute % 60, true).show();
    }

    private void saveProfile() {
        final ScheduledProfile profile;
        try {
            int mask = 0;
            for (int i = 0; i < days.length; i++) if (days[i].isChecked()) mask |= 1 << i;
            Set<String> categories = new TreeSet<>();
            for (Map.Entry<String, CheckBox> entry : categoryChecks.entrySet()) if (entry.getValue().isChecked()) categories.add(entry.getKey());
            profile = new ScheduledProfile(ui.profileName.getText().toString(),
                    new WeeklySchedule(ui.profileEnabled.isChecked(), mask, startMinute, endMinute),
                    categories, LocalRuleEdits.parseUidInput(ui.profileUids.getText().toString()));
        } catch (IllegalArgumentException e) { error(R.string.rules9_profile_error); return; }
        String original = editingName;
        PinGate.require(this, () -> mutate(() -> BlocklistStore.get().saveProfile(this, original, profile), this::newProfile));
    }

    private void deleteProfile() {
        String original = editingName;
        if (original == null) return;
        new AlertDialog.Builder(this).setTitle(R.string.rules9_delete_profile)
                .setMessage(getString(R.string.rules9_delete_confirm, original))
                .setPositiveButton(android.R.string.ok, (d, w) -> PinGate.require(this, () ->
                        mutate(() -> BlocklistStore.get().saveProfile(this, original, null), this::newProfile)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            if (intent.resolveActivity(getPackageManager()) == null) throw new ActivityNotFoundException();
            document.launch(new String[] {"application/json", "text/json", "text/plain"});
        } catch (ActivityNotFoundException e) { error(R.string.no_handler); }
    }

    private void error(int message) {
        ui.rulesStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
