package tracewall.parent;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.ListenerRegistration;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import tracewall.blocklist.RuleLists;
import tracewall.data.AppInventory;
import tracewall.data.RemoteRules;
import tracewall.data.TextFilter;
import tracewall.parent.databinding.ActivityAppPickerBinding;
import tracewall.parent.databinding.ItemAppPickerBinding;
import tracewall.parent.databinding.ItemAppPickerHeaderBinding;
import tracewall.parent.sync.FirebaseParent;

/** Choose a package from the child's consented metadata instead of guessing its Android UID. */
public class AppPickerActivity extends AppCompatActivity {
    private ActivityAppPickerBinding ui;
    private ItemAppPickerHeaderBinding header;
    private Apps adapter;
    private final RuleLists rules = new RuleLists();
    private List<AppInventory.Entry> apps = Collections.emptyList();
    private ListenerRegistration appsReg;
    private ValueEventListener rulesReg;
    private String childUid;
    private boolean started, fresh, writing;
    private boolean loading, rulesFailed, accessFailed;
    private String searchQuery = "";
    private int matchingApps;
    private int statusText = R.string.device_verifying;
    private static final String STATE_SEARCH = "app_search";
    private int connection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityAppPickerBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        childUid = ParentDevices.prefs(this).getString(PairingActivity.KEY_CHILD_UID, "");
        if (state != null) searchQuery = state.getString(STATE_SEARCH, "");
        ui.appsToolbar.setNavigationOnClickListener(v -> finish());
        adapter = new Apps();
        adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        ui.appsList.setLayoutManager(new LinearLayoutManager(this));
        ui.appsList.setItemAnimator(null);
        ui.appsList.setAdapter(new ConcatAdapter(new Header(), adapter));
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString(STATE_SEARCH, searchQuery);
        super.onSaveInstanceState(state);
    }
    @Override protected void onStart() {
        super.onStart(); started = true;
        int ticket = ++connection;
        // Never show a previous identity's app inventory while verifying a new session.
        apps = Collections.emptyList(); fresh = false; rules.pause();
        loading = true; rulesFailed = accessFailed = false;
        setStatus(R.string.device_verifying);
        render();
        FirebaseParent.get().verifyAccess(childUid, () -> {
            if (!current(ticket)) return;
            setStatus(R.string.parent_apps_loading);
            appsReg = FirebaseParent.get().listenApps(childUid, snapshot -> {
                if (!current(ticket)) return;
                apps = snapshot.entries;
                fresh = !snapshot.cached;
                loading = false;
                render();
                updateStatus();
            }, () -> {
                if (!current(ticket)) return;
                loading = false; fresh = false; apps = Collections.emptyList(); render();
                updateStatus();
            });
            rulesReg = FirebaseParent.get().listenRules(childUid, snapshot -> {
                if (!current(ticket)) return;
                try {
                    RemoteRules current = RemoteRules.parse(snapshot.getValue());
                    List<Integer> blocked = new ArrayList<>();
                    for (String uid : current.blockedUids) blocked.add(Integer.parseInt(uid));
                    rules.load(current.allow, blocked);
                    rulesFailed = false;
                } catch (IllegalArgumentException invalid) { rules.pause(); rulesFailed = true; }
                render();
                updateStatus();
            }, () -> { if (current(ticket)) { rules.pause(); rulesFailed = true; render(); updateStatus(); } });
        }, () -> {
            if (!current(ticket)) return;
            loading = false; accessFailed = true;
            setStatus(R.string.device_switch_failed); render();
        });
    }
    private boolean current(int ticket) { return started && ticket == connection && !isFinishing() && !isDestroyed(); }
    private boolean editable() { return started && fresh && rules.isReady() && !writing; }
    private void render() {
        List<AppInventory.Entry> matches = new ArrayList<>();
        for (AppInventory.Entry entry : apps) if (TextFilter.matches(searchQuery, entry.label, entry.packageName)) matches.add(entry);
        matchingApps = matches.size();
        // Rule state is independent of inventory content, so rebind the visible app rows too.
        adapter.submitList(matches, () -> adapter.notifyItemRangeChanged(0, adapter.getItemCount()));
        renderHeader();
    }
    private void setStatus(int text) { statusText = text; renderHeader(); }
    private void updateStatus() {
        setStatus(writing ? R.string.rules_pending : accessFailed ? R.string.device_switch_failed
            : rulesFailed ? R.string.rules_failed : loading ? R.string.parent_apps_loading
            : apps.isEmpty() ? R.string.apps_unavailable : !fresh ? R.string.apps_cached
            : rules.isReady() ? R.string.apps_ready : R.string.rules_waiting);
    }
    private void renderHeader() {
        if (header == null) return;
        header.appsStatus.setText(statusText);
        header.appsProgress.setVisibility(loading || (fresh && !rules.isReady() && !rulesFailed && !accessFailed) ? View.VISIBLE : View.GONE);
        header.appsRetry.setEnabled(!writing);
        header.appsEmpty.setVisibility(matchingApps == 0 ? View.VISIBLE : View.GONE);
        header.appsEmptyTitle.setText(loading ? R.string.parent_apps_loading_title
            : apps.isEmpty() ? R.string.parent_apps_empty_title : R.string.parent_apps_search_empty_title);
        header.appsEmptyBody.setText(!apps.isEmpty() ? R.string.parent_apps_search_empty : statusText);
    }
    private void choose(AppInventory.Entry entry) {
        if (!editable()) return;
        boolean block = !rules.blockedUids().contains(entry.uid);
        new AlertDialog.Builder(this).setTitle(entry.label)
            .setMessage(getString(R.string.app_toggle_body, entry.packageName, entry.uid))
            .setPositiveButton(block ? R.string.app_block_dns : R.string.app_unblock_dns, (dialog, which) -> {
                if (!editable()) return;
                boolean unchanged = false;
                for (AppInventory.Entry current : apps)
                    if (current.packageName.equals(entry.packageName) && current.uid == entry.uid) { unchanged = true; break; }
                if (!unchanged) { setStatus(R.string.apps_changed); return; }
                writing = true; render(); setStatus(R.string.rules_pending);
                int ticket = connection;
                String writerUid = FirebaseParent.get().uid();
                FirebaseParent.get().editBlockedUid(childUid, entry.uid, block, accepted -> {
                    writing = false;
                    if (!started || isFinishing() || isDestroyed()
                            || !java.util.Objects.equals(writerUid, FirebaseParent.get().uid())) return;
                    render();
                    if (current(ticket) || (fresh && rules.isReady()))
                        setStatus(accepted ? R.string.rules_saved : R.string.rules_failed);
                });
            }).setNegativeButton(android.R.string.cancel, null).show();
    }
    @Override protected void onStop() {
        started = false; connection++; fresh = false; rules.pause();
        if (appsReg != null) { appsReg.remove(); appsReg = null; }
        if (rulesReg != null) { FirebaseParent.get().removeRulesListener(childUid, rulesReg); rulesReg = null; }
        super.onStop();
    }
    /** A real RecyclerView header keeps long guidance and search reachable at large font sizes. */
    private final class Header extends RecyclerView.Adapter<Header.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            ItemAppPickerHeaderBinding binding = ItemAppPickerHeaderBinding.inflate(getLayoutInflater(), parent, false);
            binding.appsDevice.setText(getString(R.string.named_child_label, ParentDevices.name(AppPickerActivity.this, childUid), childUid));
            binding.appsSearch.setText(searchQuery);
            binding.appsSearch.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { searchQuery = s.toString(); render(); }
                public void afterTextChanged(Editable text) {}
            });
            binding.appsRetry.setOnClickListener(v -> { if (!writing) recreate(); });
            return new Holder(binding);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { header = holder.b; renderHeader(); }
        @Override public int getItemCount() { return 1; }
        final class Holder extends RecyclerView.ViewHolder {
            final ItemAppPickerHeaderBinding b;
            Holder(ItemAppPickerHeaderBinding b) { super(b.getRoot()); this.b = b; }
        }
    }
    private final class Apps extends ListAdapter<AppInventory.Entry, Apps.Holder> {
        Apps() {
            super(new DiffUtil.ItemCallback<AppInventory.Entry>() {
                public boolean areItemsTheSame(@NonNull AppInventory.Entry a, @NonNull AppInventory.Entry b) { return a.packageName.equals(b.packageName); }
                public boolean areContentsTheSame(@NonNull AppInventory.Entry a, @NonNull AppInventory.Entry b) {
                    return a.uid == b.uid && a.updatedMs == b.updatedMs && a.label.equals(b.label);
                }
            });
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(ItemAppPickerBinding.inflate(getLayoutInflater(), parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            AppInventory.Entry entry = getItem(position);
            holder.b.appName.setText(entry.label);
            holder.b.appDetails.setText(getString(R.string.app_picker_details, entry.packageName, entry.uid,
                DateFormat.getDateTimeInstance().format(new Date(entry.updatedMs))));
            boolean blocked = rules.isReady() && rules.blockedUids().contains(entry.uid);
            holder.b.appRuleState.setText(!rules.isReady() ? R.string.parent_app_rule_unknown
                : blocked ? R.string.parent_app_blocked : R.string.parent_app_not_blocked);
            holder.b.appRuleState.setTextColor(ContextCompat.getColor(AppPickerActivity.this,
                !rules.isReady() ? tracewall.auth.R.color.tw_muted
                    : blocked ? tracewall.auth.R.color.tw_danger : tracewall.auth.R.color.tw_text));
            holder.b.appAction.setText(editable() ? R.string.parent_apps_review : R.string.parent_apps_view_only);
            holder.itemView.setEnabled(editable());
            holder.itemView.setOnClickListener(v -> choose(entry));
        }
        final class Holder extends RecyclerView.ViewHolder {
            final ItemAppPickerBinding b;
            Holder(ItemAppPickerBinding b) { super(b.getRoot()); this.b = b; }
        }
    }
}
