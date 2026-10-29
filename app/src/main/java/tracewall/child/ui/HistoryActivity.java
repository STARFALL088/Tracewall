package tracewall.child.ui;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import java.util.List;

import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import tracewall.child.R;
import tracewall.child.databinding.ActivityHistoryBinding;
import tracewall.child.databinding.DialogHistoryFilterBinding;
import tracewall.child.databinding.ItemHistoryQueryBinding;
import tracewall.child.io.HistoryExport;
import tracewall.child.io.ShareFiles;
import tracewall.child.log.HistoryApps;
import tracewall.child.log.HistoryReader;
import tracewall.child.log.QueryRecord;
import tracewall.child.log.TracewallDb;
import tracewall.data.HistoryFilter;
import tracewall.data.HistoryQuery;
import tracewall.net.BoundedWorkPool;
import tracewall.net.DnsMessage;

/** Retained Room history, with a frozen insert boundary and bounded keyset pages. */
public class HistoryActivity extends AppCompatActivity {
    private static final int[] VERDICTS = {R.string.history_all_verdicts, R.string.history_allowed, R.string.history_blocked};
    private static final int[] RANGES = {R.string.history_today, R.string.history_24_hours,
        R.string.history_7_days, R.string.history_all_dates, R.string.history_custom};
    private final ThreadPoolExecutor io = BoundedWorkPool.create("tracewall-history-ui", 1, 2);
    private ActivityHistoryBinding ui;
    private HistoryReader reader;
    private HistoryApps apps;
    private Rows adapter;
    private Future<?> job;
    private int generation;
    private boolean busy, resumed;
    private String appSearch = "";
    private HistoryFilter filter;
    private HistoryFilter.Preset preset = HistoryFilter.Preset.LAST_24_HOURS;
    private long customStart, customEnd, snapshotId = -1;
    private HistoryQuery.Cursor displayedAnchor;
    private HistoryQuery.Direction displayedDirection = HistoryQuery.Direction.OLDER;
    private HistoryReader.Page page;
    private Parcelable pendingScroll;
    private File pendingShare;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        reader = new HistoryReader(TracewallDb.get(this));
        apps = new HistoryApps(this);
        adapter = new Rows();
        ui.historyList.setLayoutManager(new LinearLayoutManager(this));
        ui.historyList.setAdapter(adapter);
        adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        long now = System.currentTimeMillis();
        customStart = state == null ? now : state.getLong("start_day", now);
        customEnd = state == null ? now : state.getLong("end_day", now);
        if (state == null) {
            filter = new HistoryFilter("", HistoryFilter.Verdict.ALL,
                HistoryFilter.range(preset, now, TimeZone.getDefault(), customStart, customEnd), null);
        } else {
            preset = HistoryFilter.Preset.values()[Math.max(0, Math.min(RANGES.length - 1, state.getInt("preset", 1)))];
            appSearch = state.getString("app", "");
            filter = new HistoryFilter(state.getString("domain", ""), HistoryFilter.Verdict.values()[
                Math.max(0, Math.min(VERDICTS.length - 1, state.getInt("verdict", 0)))],
                new HistoryFilter.Range(state.getLong("from"), state.getLong("until")), state.getIntArray("uids"));
            snapshotId = state.getLong("snapshot", -1);
            long anchorId = state.getLong("anchor_id", 0);
            if (anchorId > 0) displayedAnchor = new HistoryQuery.Cursor(state.getLong("anchor_time"), anchorId);
            displayedDirection = state.getBoolean("newer", false) ? HistoryQuery.Direction.NEWER : HistoryQuery.Direction.OLDER;
            pendingScroll = state.getParcelable("scroll");
        }
        ui.historyBack.setOnClickListener(v -> finish());
        ui.historyFilters.setOnClickListener(v -> showFilters());
        ui.historyRefresh.setOnClickListener(v -> refreshSelection());
        ui.historyPrevious.setOnClickListener(v -> {
            if (page != null && !page.rows.isEmpty()) load(HistoryReader.cursor(page.rows.get(0)), HistoryQuery.Direction.NEWER, false);
        });
        ui.historyNext.setOnClickListener(v -> {
            if (page != null && !page.rows.isEmpty()) load(HistoryReader.cursor(page.rows.get(page.rows.size() - 1)), HistoryQuery.Direction.OLDER, false);
        });
        ui.historyExport.setOnClickListener(v -> PinGate.require(this, this::export));
        selectionLabel();
        load(displayedAnchor, displayedDirection, snapshotId < 0);
    }

    private void refreshSelection() {
        filter = new HistoryFilter(filter.domain, filter.verdict,
            HistoryFilter.range(preset, System.currentTimeMillis(), TimeZone.getDefault(), customStart, customEnd), null);
        snapshotId = -1;
        displayedAnchor = null;
        displayedDirection = HistoryQuery.Direction.OLDER;
        pendingScroll = null;
        page = null;
        adapter.submitList(new ArrayList<>());
        selectionLabel();
        load(null, HistoryQuery.Direction.OLDER, true);
    }

    private void load(HistoryQuery.Cursor anchor, HistoryQuery.Direction direction, boolean resolveApps) {
        int ticket = ++generation;
        HistoryFilter selection = filter;
        String search = appSearch;
        long frozen = snapshotId;
        setBusy(true);
        ui.historyStatus.setText(R.string.history_loading);
        submit(() -> {
            try {
                HistoryFilter resolved = resolveApps ? new HistoryFilter(selection.domain, selection.verdict,
                    selection.range, apps.resolve(search)) : selection;
                HistoryReader.Page result = reader.load(resolved, frozen, anchor, direction);
                List<Row> rows = new ArrayList<>();
                for (QueryRecord record : result.rows) rows.add(new Row(record, apps.label(record.uid)));
                runOnUiThread(() -> {
                    if (stale(ticket)) return;
                    filter = resolved;
                    snapshotId = result.snapshotId;
                    displayedAnchor = anchor;
                    displayedDirection = direction;
                    page = result;
                    adapter.submitList(rows, () -> {
                        if (pendingScroll != null && !rows.isEmpty()) {
                            ui.historyList.getLayoutManager().onRestoreInstanceState(pendingScroll);
                            pendingScroll = null;
                        } else ui.historyList.scrollToPosition(0);
                    });
                    ui.historyStats.setText(getString(R.string.history_stats, result.stats.total,
                        result.stats.blocked, result.stats.averageLatencyMs));
                    ui.historyStatus.setText(rows.isEmpty() ? R.string.history_empty : R.string.history_snapshot_note);
                    setBusy(false);
                });
            } catch (CancellationException ignored) { /* A newer request or destruction owns the UI. */ }
            catch (HistoryApps.TooManyMatchesException error) { failed(ticket, R.string.history_too_many_apps); }
            catch (RuntimeException error) { failed(ticket, R.string.history_error); }
        });
    }

    private void submit(Runnable task) {
        if (job != null) job.cancel(true);
        io.purge();
        job = io.submit(task);
    }

    private boolean stale(int ticket) { return ticket != generation || isFinishing() || isDestroyed(); }
    private void failed(int ticket, int message) {
        runOnUiThread(() -> { if (!stale(ticket)) { ui.historyStatus.setText(message); setBusy(false); } });
    }
    private void setBusy(boolean value) {
        busy = value;
        ui.historyProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        ui.historyFilters.setEnabled(!value);
        ui.historyRefresh.setEnabled(!value);
        ui.historyPrevious.setEnabled(!value && page != null && page.hasNewer);
        ui.historyNext.setEnabled(!value && page != null && page.hasOlder);
        ui.historyExport.setEnabled(!value && page != null && page.stats.total > 0);
    }

    private void export() {
        if (busy || page == null) return;
        int ticket = ++generation;
        HistoryFilter selected = filter;
        long frozen = snapshotId;
        setBusy(true);
        ui.historyStatus.setText(R.string.history_exporting);
        submit(() -> {
            try {
                HistoryExport.Result result = HistoryExport.write(getApplicationContext(), reader, selected, frozen);
                runOnUiThread(() -> {
                    if (stale(ticket)) { result.file.delete(); return; }
                    setBusy(false);
                    ui.historyStatus.setText(getString(R.string.history_exported, result.rows));
                    pendingShare = result.file;
                    shareWhenVisible();
                });
            } catch (CancellationException ignored) { /* The exporter deletes incomplete files. */ }
            catch (HistoryExport.ChangedException error) { failed(ticket, R.string.history_export_changed); }
            catch (java.io.IOException | RuntimeException error) { failed(ticket, R.string.history_export_failed); }
        });
    }

    private void shareWhenVisible() {
        if (!resumed || pendingShare == null) return;
        File file = pendingShare;
        pendingShare = null;
        ShareFiles.send(this, file, "text/csv");
    }
    @Override protected void onResume() { super.onResume(); resumed = true; shareWhenVisible(); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onDestroy() {
        generation++;
        if (job != null) job.cancel(true);
        io.shutdownNow();
        if (pendingShare != null) pendingShare.delete();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("domain", filter.domain);
        state.putString("app", appSearch);
        state.putInt("verdict", filter.verdict.ordinal());
        state.putInt("preset", preset.ordinal());
        state.putLong("from", filter.range.startMs);
        state.putLong("until", filter.range.endMs);
        state.putIntArray("uids", filter.uids());
        state.putLong("start_day", customStart);
        state.putLong("end_day", customEnd);
        state.putLong("snapshot", snapshotId);
        if (displayedAnchor != null) {
            state.putLong("anchor_id", displayedAnchor.id);
            state.putLong("anchor_time", displayedAnchor.timeMs);
        }
        state.putBoolean("newer", displayedDirection == HistoryQuery.Direction.NEWER);
        state.putParcelable("scroll", pendingScroll != null ? pendingScroll : ui.historyList.getLayoutManager().onSaveInstanceState());
        super.onSaveInstanceState(state);
    }

    private void selectionLabel() {
        ui.historySelection.setText(getString(R.string.history_selection,
            filter.domain.isEmpty() ? getString(R.string.history_any_domain) : filter.domain,
            appSearch.isEmpty() ? getString(R.string.history_any_app) : appSearch,
            getString(VERDICTS[filter.verdict.ordinal()]), getString(RANGES[preset.ordinal()])));
    }

    private void showFilters() {
        DialogHistoryFilterBinding b = DialogHistoryFilterBinding.inflate(getLayoutInflater());
        b.historyDomain.setText(filter.domain);
        b.historyApp.setText(appSearch);
        b.historyVerdictFilter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names(VERDICTS)));
        b.historyRange.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names(RANGES)));
        b.historyVerdictFilter.setSelection(filter.verdict.ordinal());
        b.historyRange.setSelection(preset.ordinal());
        long[] days = {customStart, customEnd};
        Runnable dates = () -> {
            DateFormat format = DateFormat.getDateInstance();
            b.historyStartDate.setText(getString(R.string.history_start_date, format.format(new Date(days[0]))));
            b.historyEndDate.setText(getString(R.string.history_end_date, format.format(new Date(days[1]))));
            b.historyCustomDates.setVisibility(b.historyRange.getSelectedItemPosition() == HistoryFilter.Preset.CUSTOM.ordinal() ? View.VISIBLE : View.GONE);
        };
        dates.run();
        b.historyStartDate.setOnClickListener(v -> chooseDate(days[0], value -> { days[0] = value; dates.run(); }));
        b.historyEndDate.setOnClickListener(v -> chooseDate(days[1], value -> { days[1] = value; dates.run(); }));
        b.historyRange.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { dates.run(); }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.history_filters).setView(b.getRoot())
            .setPositiveButton(R.string.history_apply, null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                HistoryFilter.exactUid(b.historyApp.getText().toString());
                HistoryFilter.Preset choice = HistoryFilter.Preset.values()[b.historyRange.getSelectedItemPosition()];
                HistoryFilter.Range range = HistoryFilter.range(choice, System.currentTimeMillis(), TimeZone.getDefault(), days[0], days[1]);
                filter = new HistoryFilter(b.historyDomain.getText().toString(), HistoryFilter.Verdict.values()[
                    b.historyVerdictFilter.getSelectedItemPosition()], range, null);
                appSearch = b.historyApp.getText().toString().trim();
                preset = choice;
                customStart = days[0]; customEnd = days[1];
                snapshotId = -1; displayedAnchor = null; displayedDirection = HistoryQuery.Direction.OLDER;
                pendingScroll = null; page = null;
                adapter.submitList(new ArrayList<>());
                selectionLabel(); dialog.dismiss();
                load(null, HistoryQuery.Direction.OLDER, true);
            } catch (IllegalArgumentException error) {
                b.historyFilterError.setText(R.string.history_filter_invalid);
                b.historyFilterError.setVisibility(View.VISIBLE);
            }
        }));
        dialog.show();
    }
    private String[] names(int[] resources) {
        String[] names = new String[resources.length];
        for (int i = 0; i < names.length; i++) names[i] = getString(resources[i]);
        return names;
    }
    private void chooseDate(long previous, Consumer<Long> selected) {
        Calendar date = Calendar.getInstance(); date.setTimeInMillis(previous);
        new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar chosen = Calendar.getInstance(); chosen.clear(); chosen.set(year, month, day);
            selected.accept(chosen.getTimeInMillis());
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static final class Row {
        final QueryRecord record; final String app;
        Row(QueryRecord record, String app) { this.record = record; this.app = app; }
    }
    private final class Rows extends ListAdapter<Row, Rows.Holder> {
        Rows() {
            super(new DiffUtil.ItemCallback<Row>() {
                public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) { return a.record.id == b.record.id; }
                public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
                    return a.record.timeMs == b.record.timeMs && a.record.blocked == b.record.blocked
                        && a.record.type == b.record.type && a.record.uid == b.record.uid
                        && a.record.latencyMs == b.record.latencyMs && Objects.equals(a.app, b.app)
                        && Objects.equals(a.record.host, b.record.host) && Objects.equals(a.record.policy, b.record.policy)
                        && Objects.equals(a.record.resolver, b.record.resolver);
                }
            });
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(ItemHistoryQueryBinding.inflate(getLayoutInflater(), parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = getItem(position); QueryRecord r = row.record;
            holder.b.historyRowHost.setText(r.host);
            holder.b.historyRowVerdict.setText(r.blocked ? R.string.verdict_block : R.string.verdict_allow);
            holder.b.historyRowVerdict.setTextColor(getColor(r.blocked ? R.color.tracewall_block : R.color.tracewall_allow));
            holder.b.historyRowMeta.setText(getString(R.string.history_row_meta, DnsMessage.typeName(r.type), row.app,
                r.uid, r.latencyMs, r.policy, r.resolver));
            holder.b.historyRowTime.setText(DateFormat.getDateTimeInstance().format(new Date(r.timeMs)));
        }
        final class Holder extends RecyclerView.ViewHolder {
            final ItemHistoryQueryBinding b;
            Holder(ItemHistoryQueryBinding binding) { super(binding.getRoot()); b = binding; }
        }
    }
}
