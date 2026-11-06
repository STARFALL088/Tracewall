package tracewall.child.ui;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private MenuItem refreshAction, exportAction;
    private int loadFailureMessage;
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
        ui.historyBack.setNavigationOnClickListener(v -> finish());
        refreshAction = ui.historyBack.getMenu().add(Menu.NONE, 1, 0, R.string.detail_history_refresh);
        refreshAction.setIcon(tracewall.auth.R.drawable.ic_tw_refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        refreshAction.setOnMenuItemClickListener(item -> { refreshSelection(); return true; });
        exportAction = ui.historyBack.getMenu().add(Menu.NONE, 2, 1, R.string.detail_history_export);
        exportAction.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        exportAction.setOnMenuItemClickListener(item -> {
            PinGate.require(this, this::export);
            return true;
        });
        ui.historyBack.getMenu().add(Menu.NONE, 3, 2, R.string.detail_history_about)
            .setOnMenuItemClickListener(item -> {
                new MaterialAlertDialogBuilder(this).setTitle(R.string.detail_history_about)
                    .setMessage(R.string.detail_history_about_body)
                    .setPositiveButton(android.R.string.ok, null).show();
                return true;
            });
        ui.historyFilters.setOnClickListener(v -> showFilters());
        ui.historyEmptyAction.setOnClickListener(v -> {
            if (loadFailureMessage != 0 && loadFailureMessage != R.string.history_too_many_apps) refreshSelection();
            else showFilters();
        });
        ui.historyPrevious.setOnClickListener(v -> {
            if (page != null && !page.rows.isEmpty()) load(HistoryReader.cursor(page.rows.get(0)), HistoryQuery.Direction.NEWER, false);
        });
        ui.historyNext.setOnClickListener(v -> {
            if (page != null && !page.rows.isEmpty()) load(HistoryReader.cursor(page.rows.get(page.rows.size() - 1)), HistoryQuery.Direction.OLDER, false);
        });
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
        ui.historySummaryBar.setExpanded(true, false);
        selectionLabel();
        load(null, HistoryQuery.Direction.OLDER, true);
    }

    private void load(HistoryQuery.Cursor anchor, HistoryQuery.Direction direction, boolean resolveApps) {
        int ticket = ++generation;
        HistoryFilter selection = filter;
        String search = appSearch;
        long frozen = snapshotId;
        loadFailureMessage = 0;
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
                        if (stale(ticket)) return;
                        if (pendingScroll != null && !rows.isEmpty()) {
                            ui.historyList.getLayoutManager().onRestoreInstanceState(pendingScroll);
                            pendingScroll = null;
                        } else ui.historyList.scrollToPosition(0);
                    });
                    ui.historyStats.setText(getString(R.string.detail_history_stats, result.stats.total,
                        result.stats.blocked, result.stats.averageLatencyMs));
                    ui.historyStatus.setText(R.string.detail_history_snapshot_note);
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
        runOnUiThread(() -> {
            if (!stale(ticket)) {
                loadFailureMessage = message;
                ui.historyStatus.setText(message);
                setBusy(false);
            }
        });
    }
    private void setBusy(boolean value) {
        busy = value;
        ui.historyProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        ui.historyFilters.setEnabled(!value);
        refreshAction.setEnabled(!value);
        ui.historyPrevious.setEnabled(!value && page != null && page.hasNewer);
        ui.historyNext.setEnabled(!value && page != null && page.hasOlder);
        exportAction.setEnabled(!value && page != null && page.stats.total > 0);
        ui.historyStats.setVisibility(page != null && page.stats.total > 0 ? View.VISIBLE : View.GONE);
        renderListState();
    }

    private void renderListState() {
        boolean hasRows = page != null && !page.rows.isEmpty();
        ui.historyList.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        ui.historyPages.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        ui.historyEmpty.setVisibility(hasRows ? View.GONE : View.VISIBLE);
        if (hasRows) return;
        boolean hasSearch = !filter.domain.isEmpty() || !appSearch.isEmpty()
                || filter.verdict != HistoryFilter.Verdict.ALL;
        ui.historyEmptyTitle.setText(busy ? R.string.detail_history_loading_title
                : loadFailureMessage != 0 ? R.string.detail_history_error_title
                : hasSearch ? R.string.detail_history_no_results_title : R.string.detail_history_empty_title);
        ui.historyEmptyMessage.setText(busy ? R.string.detail_history_loading_body
                : loadFailureMessage != 0 ? loadFailureMessage
                : hasSearch ? R.string.detail_history_no_results_body : R.string.detail_history_empty_body);
        ui.historyEmptyAction.setVisibility(busy ? View.GONE : View.VISIBLE);
        ui.historyEmptyAction.setText(loadFailureMessage != 0 && loadFailureMessage != R.string.history_too_many_apps
                ? R.string.detail_retry : R.string.detail_history_change_filters);
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
        String range = preset == HistoryFilter.Preset.CUSTOM
                ? getString(R.string.detail_history_custom_selection,
                    DateFormat.getDateInstance().format(new Date(customStart)),
                    DateFormat.getDateInstance().format(new Date(customEnd)))
                : getString(RANGES[preset.ordinal()]);
        ui.historySelection.setText(getString(R.string.detail_history_selection,
                range, getString(VERDICTS[filter.verdict.ordinal()])));
        ui.historyFilters.setText(!filter.domain.isEmpty() || !appSearch.isEmpty()
                || filter.verdict != HistoryFilter.Verdict.ALL || preset != HistoryFilter.Preset.LAST_24_HOURS
                ? R.string.detail_history_edit_filters : R.string.history_filters);
        ui.historyFilters.setContentDescription(getString(R.string.detail_history_search_selection,
            range, getString(VERDICTS[filter.verdict.ordinal()]),
            filter.domain.isEmpty() ? getString(R.string.history_any_domain) : filter.domain,
            appSearch.isEmpty() ? getString(R.string.history_any_app) : appSearch));
    }

    private void showFilters() {
        DialogHistoryFilterBinding b = DialogHistoryFilterBinding.inflate(getLayoutInflater());
        b.historyDomain.setText(filter.domain);
        b.historyApp.setText(appSearch);
        b.historyVerdictFilter.setAdapter(choices(VERDICTS));
        b.historyRange.setAdapter(choices(RANGES));
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
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.history_filters).setView(b.getRoot())
            .setPositiveButton(R.string.history_apply, null).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.detail_history_reset_filters, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                b.historyDomain.setText("");
                b.historyApp.setText("");
                b.historyVerdictFilter.setSelection(HistoryFilter.Verdict.ALL.ordinal());
                b.historyRange.setSelection(HistoryFilter.Preset.LAST_24_HOURS.ordinal());
                b.historyFilterError.setVisibility(View.GONE);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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
                    ui.historySummaryBar.setExpanded(true, false);
                    selectionLabel(); dialog.dismiss();
                    load(null, HistoryQuery.Direction.OLDER, true);
                } catch (IllegalArgumentException error) {
                    b.historyFilterError.setText(R.string.history_filter_invalid);
                    b.historyFilterError.setVisibility(View.VISIBLE);
                    b.historyFilterScroll.post(() -> b.historyFilterScroll.smoothScrollTo(0, b.historyFilterError.getBottom()));
                }
            });
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    /** Allow Bengali labels and large text to wrap instead of clipping inside a spinner. */
    private ArrayAdapter<String> choices(int[] resources) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names(resources)) {
            @NonNull @Override public View getView(int position, View recycled, @NonNull ViewGroup parent) {
                return readableChoice(super.getView(position, recycled, parent));
            }
            @Override public View getDropDownView(int position, View recycled, @NonNull ViewGroup parent) {
                return readableChoice(super.getDropDownView(position, recycled, parent));
            }
        };
    }
    private View readableChoice(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setSingleLine(false);
            text.setMaxLines(Integer.MAX_VALUE);
            text.setTextAppearance(tracewall.auth.R.style.TextAppearance_Tracewall_Body);
            text.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        }
        return view;
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
            holder.b.historyRowVerdict.setText(r.blocked ? R.string.detail_verdict_blocked : R.string.detail_verdict_allowed);
            holder.b.historyRowVerdict.setTextColor(getColor(r.blocked
                    ? tracewall.auth.R.color.tw_danger : tracewall.auth.R.color.tw_success));
            holder.b.historyRowMeta.setText(getString(R.string.detail_history_row_meta, DnsMessage.typeName(r.type), row.app,
                r.uid, r.latencyMs, r.policy, r.resolver));
            holder.b.historyRowTime.setText(DateFormat.getDateTimeInstance().format(new Date(r.timeMs)));
        }
        final class Holder extends RecyclerView.ViewHolder {
            final ItemHistoryQueryBinding b;
            Holder(ItemHistoryQueryBinding binding) { super(binding.getRoot()); b = binding; }
        }
    }
}
