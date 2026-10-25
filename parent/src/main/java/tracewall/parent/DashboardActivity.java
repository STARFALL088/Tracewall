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
import java.util.LinkedHashMap;
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

        // Category toggles: each change immediately pushes the full category
        // map so the child reloads with the new active set.
        CompoundButton.OnCheckedChangeListener catListener = (btn, checked) ->
            pushCurrentCategories();
        ui.catMalware.setOnCheckedChangeListener(catListener);
        ui.catGambling.setOnCheckedChangeListener(catListener);
        ui.catAdult.setOnCheckedChangeListener(catListener);
        ui.catAds.setOnCheckedChangeListener(catListener);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (childUid.isEmpty()) return;
        historyReg = FirebaseParent.get().listenHistory(
            childUid,
            HISTORY_LIMIT,
            records -> adapter.submit(records)
        );
    }

    @Override
    protected void onStop() {
        super.onStop();
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

    private void pushAllowRule() {
        String domain = ui.allowInput
            .getText()
            .toString()
            .trim()
            .toLowerCase(Locale.ROOT);
        if (TextUtils.isEmpty(domain)) {
            ui.allowInput.setError(getString(R.string.rule_domain_empty));
            return;
        }
        FirebaseParent.get().pushRules(
            childUid,
            Collections.singletonList(domain),
            Collections.emptyList()
        );
        ui.allowInput.setText("");
        Toast.makeText(
            this,
            getString(R.string.rule_pushed, domain),
            Toast.LENGTH_SHORT
        ).show();
    }

    private void pushCurrentCategories() {
        if (childUid.isEmpty()) return;
        Map<String, Boolean> cats = new LinkedHashMap<>();
        cats.put("malware", ui.catMalware.isChecked());
        cats.put("gambling", ui.catGambling.isChecked());
        cats.put("adult", ui.catAdult.isChecked());
        cats.put("ads", ui.catAds.isChecked());
        FirebaseParent.get().pushCategories(childUid, cats);
        Toast.makeText(
            this,
            R.string.categories_pushed,
            Toast.LENGTH_SHORT
        ).show();
    }

    private void pushBlockUidRule() {
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
        FirebaseParent.get().pushRules(
            childUid,
            Collections.emptyList(),
            Collections.singletonList(uid)
        );
        ui.blockUidInput.setText("");
        Toast.makeText(
            this,
            getString(R.string.rule_uid_pushed, uid),
            Toast.LENGTH_SHORT
        ).show();
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

            String host = String.valueOf(r.getOrDefault("host", "?"));
            boolean blocked = Boolean.TRUE.equals(r.get("blocked"));
            String policy = String.valueOf(r.getOrDefault("policy", ""));

            h.b.time.setText(time);
            h.b.host.setText(host);
            h.b.verdict.setText(blocked ? "BLOCK" : "allow");
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
