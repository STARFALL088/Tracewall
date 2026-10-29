package tracewall.parent;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
import tracewall.parent.sync.FirebaseParent;

/** Choose a package from the child's consented metadata instead of guessing its Android UID. */
public class AppPickerActivity extends AppCompatActivity {
    private ActivityAppPickerBinding ui;
    private Apps adapter;
    private final RuleLists rules = new RuleLists();
    private List<AppInventory.Entry> apps = Collections.emptyList();
    private ListenerRegistration appsReg;
    private ValueEventListener rulesReg;
    private String childUid;
    private boolean started, fresh, writing;
    private int connection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityAppPickerBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        childUid = ParentDevices.prefs(this).getString(PairingActivity.KEY_CHILD_UID, "");
        ui.appsDevice.setText(getString(R.string.named_child_label, ParentDevices.name(this, childUid), childUid));
        ui.appsBack.setOnClickListener(v -> finish());
        adapter = new Apps();
        adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        ui.appsList.setLayoutManager(new LinearLayoutManager(this));
        ui.appsList.setAdapter(adapter);
        ui.appsSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable text) {}
        });
    }
    @Override protected void onStart() {
        super.onStart(); started = true;
        int ticket = ++connection;
        render();
        ui.appsStatus.setText(R.string.device_verifying);
        FirebaseParent.get().verifyAccess(childUid, () -> {
            if (!current(ticket)) return;
            appsReg = FirebaseParent.get().listenApps(childUid, snapshot -> {
                if (!current(ticket)) return;
                apps = snapshot.entries;
                fresh = !snapshot.cached;
                render();
                if (!writing) ui.appsStatus.setText(apps.isEmpty() ? R.string.apps_unavailable
                    : !fresh ? R.string.apps_cached : rules.isReady() ? R.string.apps_ready : R.string.rules_waiting);
            }, () -> {
                if (!current(ticket)) return;
                fresh = false; apps = Collections.emptyList(); render();
                ui.appsStatus.setText(R.string.apps_unavailable);
            });
            rulesReg = FirebaseParent.get().listenRules(childUid, snapshot -> {
                if (!current(ticket)) return;
                try {
                    RemoteRules current = RemoteRules.parse(snapshot.getValue());
                    List<Integer> blocked = new ArrayList<>();
                    for (String uid : current.blockedUids) blocked.add(Integer.parseInt(uid));
                    rules.load(current.allow, blocked);
                } catch (IllegalArgumentException invalid) { rules.pause(); }
                render();
                if (!writing) ui.appsStatus.setText(!rules.isReady() ? R.string.rules_failed
                    : apps.isEmpty() ? R.string.apps_unavailable : fresh ? R.string.apps_ready : R.string.apps_cached);
            }, () -> { if (current(ticket)) { rules.pause(); render(); ui.appsStatus.setText(R.string.rules_failed); } });
        }, () -> { if (current(ticket)) ui.appsStatus.setText(R.string.device_switch_failed); });
    }
    private boolean current(int ticket) { return started && ticket == connection && !isFinishing() && !isDestroyed(); }
    private boolean editable() { return started && fresh && rules.isReady() && !writing; }
    private void render() {
        List<AppInventory.Entry> matches = new ArrayList<>();
        String search = ui.appsSearch.getText().toString();
        for (AppInventory.Entry entry : apps) if (TextFilter.matches(search, entry.label, entry.packageName)) matches.add(entry);
        adapter.submitList(matches);
        ui.appsList.setAlpha(editable() ? 1f : .55f);
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
                if (!unchanged) { ui.appsStatus.setText(R.string.apps_changed); return; }
                writing = true; render(); ui.appsStatus.setText(R.string.rules_pending);
                int ticket = connection;
                FirebaseParent.get().editBlockedUid(childUid, entry.uid, block, accepted -> {
                    writing = false;
                    if (!started || isFinishing() || isDestroyed()) return;
                    render();
                    if (current(ticket) || (fresh && rules.isReady()))
                        ui.appsStatus.setText(accepted ? R.string.rules_saved : R.string.rules_failed);
                });
            }).setNegativeButton(android.R.string.cancel, null).show();
    }
    @Override protected void onStop() {
        started = false; connection++; fresh = false; rules.pause();
        if (appsReg != null) { appsReg.remove(); appsReg = null; }
        if (rulesReg != null) { FirebaseParent.get().removeRulesListener(childUid, rulesReg); rulesReg = null; }
        super.onStop();
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
            holder.itemView.setOnClickListener(v -> choose(entry));
        }
        final class Holder extends RecyclerView.ViewHolder {
            final ItemAppPickerBinding b;
            Holder(ItemAppPickerBinding b) { super(b.getRoot()); this.b = b; }
        }
    }
}
