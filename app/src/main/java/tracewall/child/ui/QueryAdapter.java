package tracewall.child.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import tracewall.child.R;
import tracewall.child.databinding.ItemQueryBinding;
import tracewall.child.log.AppLabels;
import tracewall.child.log.QueryLog;
import tracewall.net.DnsMessage;

/**
 * The query history list.
 *
 * ViewHolder + item layout + adapter, the Week 9 trio. Rebinding the whole
 * list on each tick rather than diffing: the ring is capped at 500 and the
 * visible window is ~14, so a diff would be more code than it saves.
 */
public final class QueryAdapter extends RecyclerView.Adapter<QueryAdapter.Holder> {

    private final List<QueryLog.Entry> items = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final AppLabels labels;
    private final OnAllow onAllow;

    /** Long-press on a blocked row offers to allow that host. */
    public interface OnAllow {
        void allow(String host);
    }

    public QueryAdapter(AppLabels labels, OnAllow onAllow) {
        this.labels = labels;
        this.onAllow = onAllow;
    }

    public void submit(List<QueryLog.Entry> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemQueryBinding b = ItemQueryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        QueryLog.Entry e = items.get(position);
        boolean blocked = e.blocked;

        h.b.verdict.setText(blocked ? R.string.verdict_block : R.string.verdict_allow);
        h.b.verdict.setTextColor(h.b.getRoot().getContext().getColor(
                blocked ? R.color.tracewall_block : R.color.tracewall_allow));

        h.b.host.setText(e.host);
        h.b.meta.setText(e.blocked
                ? h.b.getRoot().getContext().getString(R.string.meta_blocked,
                        DnsMessage.typeName(e.type), labels.label(e.uid),
                        e.latencyMs, e.policy)
                : h.b.getRoot().getContext().getString(R.string.meta,
                        DnsMessage.typeName(e.type), labels.label(e.uid), e.latencyMs));

        h.b.getRoot().setOnLongClickListener(v -> {
            if (!e.blocked || onAllow == null) return false;
            onAllow.allow(e.host);
            return true;
        });
        h.b.time.setText(clock.format(new Date(e.timeMs)));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemQueryBinding b;

        Holder(ItemQueryBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
