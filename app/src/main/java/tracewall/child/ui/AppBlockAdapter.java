package tracewall.child.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tracewall.child.databinding.ItemAppBinding;

/**
 * The installed-apps list behind the per-app block screen.
 *
 * The uid is the identity that matters: it is what every DNS lookup is
 * attributed to, and what {@code PolicyEngine} matches on, so the package
 * name is display only. Moving a switch touches prefs and reloads the live
 * policy, so a newly blocked app sinks on its very next lookup.
 *
 * Rebinding the whole list on each change rather than diffing - mirrors
 * {@link QueryAdapter}, and a filter change rebuilds the shown set anyway.
 */
public final class AppBlockAdapter extends RecyclerView.Adapter<AppBlockAdapter.Holder> {

    /** One installed app. Toggled state lives here, the count derives from it. */
    public static final class App {
        public final String label;
        public final String pkg;
        public final int uid;
        public boolean blocked;

        App(String label, String pkg, int uid, boolean blocked) {
            this.label = label;
            this.pkg = pkg;
            this.uid = uid;
            this.blocked = blocked;
        }
    }

    /** A switch moved; the screen persists it and refreshes its count. */
    public interface OnToggle {
        void toggle(App app, boolean block);
    }

    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final OnToggle onToggle;
    private String filter = "";

    public AppBlockAdapter(OnToggle onToggle) {
        this.onToggle = onToggle;
    }

    public void submit(List<App> apps) {
        all.clear();
        if (apps != null) all.addAll(apps);
        apply();
    }

    public void setFilter(String text) {
        filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        apply();
    }

    public int blockedCount() {
        int n = 0;
        for (App a : all) if (a.blocked) n++;
        return n;
    }

    private void apply() {
        shown.clear();
        for (App a : all) {
            if (filter.isEmpty()
                    || a.label.toLowerCase(Locale.ROOT).contains(filter)
                    || a.pkg.contains(filter)) {
                shown.add(a);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAppBinding b = ItemAppBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        App a = shown.get(position);
        h.b.appLabel.setText(a.label);
        h.b.appPkg.setText(a.pkg);
        // Detach the old listener before setting the checked state: a recycled
        // switch would otherwise re-fire the change while scrolling.
        h.b.appSwitch.setOnCheckedChangeListener(null);
        h.b.appSwitch.setChecked(a.blocked);
        h.b.appSwitch.setOnCheckedChangeListener((button, on) -> {
            a.blocked = on;
            if (onToggle != null) onToggle.toggle(a, on);
        });
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemAppBinding b;

        Holder(ItemAppBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
