package tracewall.parent;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.firestore.ListenerRegistration;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tracewall.parent.databinding.ActivityDashboardBinding;
import tracewall.parent.databinding.ItemHistoryBinding;
import tracewall.parent.sync.FirebaseParent;

/**
 * Main screen after pairing. Shows the child's recent DNS history from
 * Firestore and lets the parent push allow / block-UID rules to the child
 * via Realtime Database.
 *
 * The Firestore listener is attached in onStart and removed in onStop to
 * avoid leaking it when the activity is in the back stack.
 */
public class DashboardActivity extends AppCompatActivity {

    private static final int HISTORY_LIMIT = 50;

    private ActivityDashboardBinding ui;
    private HistoryAdapter adapter;
    private ListenerRegistration historyReg;
    private ListenerRegistration eventsReg;
    private String latestEventId;
    private List<Map<String, Object>> recentHistory = Collections.emptyList();
    private List<Map<String, Object>> recentEvents = Collections.emptyList();
    private void renderFeed() { adapter.submit(tracewall.data.ActivityFeed.merge(recentHistory, recentEvents, 100)); }
    private final tracewall.blocklist.RuleLists rules = new tracewall.blocklist.RuleLists();
    private com.google.firebase.database.ValueEventListener rulesReg;
    private boolean renderingRules;
    private boolean writingRules;
    private String childUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());

        childUid = prefs().getString(PairingActivity.KEY_CHILD_UID, "");
        ui.childUidLabel.setText(
            getString(R.string.dashboard_connected, childUid)
        );

        adapter = new HistoryAdapter();
        ui.historyList.setLayoutManager(new LinearLayoutManager(this));
        ui.historyList.setAdapter(adapter);

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
        if (childUid.isEmpty()) return;
        rules.pause();
        setRuleControls(false);
        if (!writingRules) ui.ruleStatus.setText(R.string.rules_waiting);
        rulesReg = FirebaseParent.get().listenRules(childUid, snapshot -> {
            try {
                tracewall.data.RemoteRules current = tracewall.data.RemoteRules.parse(snapshot.getValue());
                List<Integer> uids = new ArrayList<>();
                for (String uid : current.blockedUids) uids.add(Integer.parseInt(uid));
                rules.load(current.allow, uids);
                renderingRules = true;
                ui.catMalware.setChecked(current.categories == null || current.categories.contains("malware"));
                ui.catGambling.setChecked(current.categories == null || current.categories.contains("gambling"));
                ui.catAdult.setChecked(current.categories == null || current.categories.contains("adult"));
                ui.catAds.setChecked(current.categories == null || current.categories.contains("ads"));
                renderingRules = false;
                setRuleControls(true);
                if (!writingRules) ui.ruleStatus.setText(R.string.rules_ready);
            } catch (IllegalArgumentException e) {
                rules.pause();
                setRuleControls(false);
                ui.ruleStatus.setText(R.string.rules_failed);
            }
        }, () -> { rules.pause(); setRuleControls(false); ui.ruleStatus.setText(R.string.rules_failed); });
        historyReg = FirebaseParent.get().listenHistory(
            childUid,
            HISTORY_LIMIT,
            records -> {
                recentHistory = records;
                renderFeed();
                if (!records.isEmpty()) {
                    ui.lastSeen.setText(getString(R.string.last_seen,
                        formatTime(records.get(0).get("time_ms"))));
                } else ui.lastSeen.setText(R.string.last_seen_unknown);
            }
        );
        eventsReg = FirebaseParent.get().listenEvents(childUid, events -> {
            ui.tamperBanner.setVisibility(android.view.View.GONE);
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
            ui.tamperBanner.setVisibility(android.view.View.VISIBLE);
        });
        ui.dismissTamper.setOnClickListener(v -> {
            if (latestEventId != null) prefs().edit()
                .putString("dismissed_event_" + childUid, latestEventId).apply();
            ui.tamperBanner.setVisibility(android.view.View.GONE);
        });
    }

    private static String formatTime(Object value) {
        long time = value instanceof Number ? ((Number) value).longValue() : 0;
        return java.text.DateFormat.getDateTimeInstance().format(new Date(time));
    }

    @Override
    protected void onStop() {
        super.onStop();
        rules.pause();
        setRuleControls(false);
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
        menu.add(Menu.NONE, 1, Menu.NONE, R.string.menu_unpair);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            confirmUnpair();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ----------------------------------------------------------------- rules

    private void setRuleControls(boolean enabled) {
        enabled = enabled && !writingRules;
        ui.allowButton.setEnabled(enabled);
        ui.blockUidButton.setEnabled(enabled);
        ui.editRules.setEnabled(enabled);
        ui.catMalware.setEnabled(enabled);
        ui.catGambling.setEnabled(enabled);
        ui.catAdult.setEnabled(enabled);
        ui.catAds.setEnabled(enabled);
    }

    private FirebaseParent.WriteListener beginWrite() {
        writingRules = true;
        setRuleControls(false);
        ui.ruleStatus.setText(R.string.rules_pending);
        return accepted -> {
            writingRules = false;
            if (isFinishing() || isDestroyed()) return;
            setRuleControls(rules.isReady());
            ui.ruleStatus.setText(accepted ? R.string.rules_saved : R.string.rules_failed);
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
            .setMessage(R.string.unpair_body)
            .setPositiveButton(R.string.unpair_yes, (d, w) -> unpair())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void unpair() {
        prefs().edit().remove(PairingActivity.KEY_CHILD_UID).apply();
        finish();
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
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(
                new Date(timeMs)
            );

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
