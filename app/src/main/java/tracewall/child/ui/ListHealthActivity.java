package tracewall.child.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import tracewall.child.R;
import tracewall.child.databinding.ActivityListHealthBinding;
import tracewall.child.databinding.ItemFeedHealthBinding;
import tracewall.child.net.ListUpdater;
import tracewall.data.FeedHealth;
import tracewall.data.FeedSource;
import tracewall.data.FeedUpdate;

/** Read-only on entry. All local policy changes, shrink approvals and explicit refreshes use PinGate. */
public final class ListHealthActivity extends AppCompatActivity {
    private ActivityListHealthBinding ui;
    private final List<ItemFeedHealthBinding> cards = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private final Runnable poll = this::render;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> main.post(() -> {
        if (started) render();
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = ActivityListHealthBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        androidx.core.view.ViewCompat.setAccessibilityHeading(ui.feedsHeading, true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(ui.feedsPolicy, true);
        ui.feedsBack.setOnClickListener(v -> finish());
        ui.feedsChangePolicy.setOnClickListener(v -> choosePolicy());
        ui.feedsRefresh.setOnClickListener(v -> PinGate.require(this, this::refresh));
        for (FeedSource source : FeedSource.ALL) {
            ItemFeedHealthBinding card = ItemFeedHealthBinding.inflate(getLayoutInflater(), ui.feedsCards, true);
            card.feedsCategory.setText(category(source));
            androidx.core.view.ViewCompat.setAccessibilityHeading(card.feedsCategory, true);
            card.feedsApprove.setText(getString(R.string.feeds_approve_category, category(source)));
            card.feedsApprove.setOnClickListener(v -> confirmShrink(source));
            cards.add(card);
        }
        render();
    }
    @Override protected void onStart() {
        super.onStart(); started = true;
        ListUpdater.prefs(this).registerOnSharedPreferenceChangeListener(changed);
        render();
    }
    @Override protected void onStop() {
        started = false;
        ListUpdater.prefs(this).unregisterOnSharedPreferenceChangeListener(changed);
        main.removeCallbacksAndMessages(null);
        super.onStop();
    }
    private boolean live() { return !isFinishing() && !isDestroyed(); }
    private void choosePolicy() {
        FeedUpdate.Policy target = ListUpdater.retentionPolicy(this) == FeedUpdate.Policy.RETAIN_BUNDLED
            ? FeedUpdate.Policy.FEED_ONLY : FeedUpdate.Policy.RETAIN_BUNDLED;
        new AlertDialog.Builder(this).setTitle(policy(target))
            .setMessage(target == FeedUpdate.Policy.FEED_ONLY ? R.string.feeds_confirm_only : R.string.feeds_confirm_retain)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.feeds_confirm, (d, which) -> PinGate.require(this, () ->
                ListUpdater.setRetentionAfterPin(this, target, ok -> {
                    if (!live()) return;
                    Toast.makeText(this, ok ? R.string.feeds_saved : R.string.feeds_setting_failed, Toast.LENGTH_LONG).show();
                    render();
                }))).show();
    }
    private void confirmShrink(FeedSource source) {
        FeedHealth health = ListUpdater.health(this, source);
        if (health.status() != FeedHealth.Code.SHRINK) { render(); return; }
        String digest = health.metadata("pendingDigest");
        new AlertDialog.Builder(this).setTitle(R.string.feeds_approve_title)
            .setMessage(getString(R.string.feeds_approve_body, category(source), health.count("baselineCount"), health.count("pendingCount"), digest))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.feeds_confirm, (d, which) -> PinGate.require(this, () ->
                ListUpdater.approveShrinkAfterPin(this, source, digest, ok -> {
                    if (!live()) return;
                    if (ok) refresh();
                    else Toast.makeText(this, R.string.feeds_setting_failed, Toast.LENGTH_LONG).show();
                    render();
                }))).show();
    }
    private void refresh() {
        ListUpdater.update(this, new ListUpdater.Listener() {
            @Override public void progress(int completed, int total) {
                if (!live()) return;
                text(ui.feedsOperation, getString(R.string.feeds_progress, completed, total));
                render();
            }
            @Override public void done(ListUpdater.Result result) {
                if (!live()) return;
                text(ui.feedsOperation, result.busy ? getString(R.string.feeds_busy)
                    : getString(R.string.feeds_finished, result.updated, result.failed, result.added, result.removed));
                render();
            }
        });
        render();
    }
    private void render() {
        if (ui == null || !live()) return;
        boolean busy = ListUpdater.isRunning();
        ui.feedsChangePolicy.setEnabled(!busy); ui.feedsRefresh.setEnabled(!busy);
        text(ui.feedsPolicy, getString(R.string.feeds_policy_current, policy(ListUpdater.retentionPolicy(this))));
        for (int i = 0; i < cards.size(); i++) {
            FeedSource source = FeedSource.ALL.get(i);
            FeedHealth h = ListUpdater.health(this, source);
            ItemFeedHealthBinding card = cards.get(i);
            String status = h.status() == FeedHealth.Code.RUNNING && !busy ? getString(R.string.feeds_interrupted) : code(h.status(), h.count("http"));
            text(card.feedsStatus, getString(R.string.feeds_status, status));
            String times = getString(R.string.feeds_times, time(h.attempted()), time(h.succeeded()), time(h.failed()), code(h.failure(), h.count("failureHttp")));
            String counts = h.succeeded() == 0 ? getString(R.string.feeds_no_counts) : getString(R.string.feeds_counts,
                h.count("feedCount"), h.count("activeCount"), h.count("rawBytes"), h.count("mergedBytes"), h.count("lines"),
                h.count("duplicates"), h.count("added"), h.count("removed"), policy(FeedUpdate.Policy.valueOf(h.metadata("policy"))));
            text(card.feedsDetails, times + "\n\n" + counts);
            text(card.feedsSource, getString(R.string.feeds_source_info, source.url, source.attribution,
                h.attempted() == 0 ? getString(R.string.feeds_unknown) : known(h.metadata("url")),
                h.count("http") == 0 ? getString(R.string.feeds_unknown) : Integer.toString(h.count("http")),
                known(h.metadata("etag")), known(h.metadata("modified"))));
            card.feedsApprove.setVisibility(h.status() == FeedHealth.Code.SHRINK ? View.VISIBLE : View.GONE);
            card.feedsApprove.setEnabled(!busy);
        }
        main.removeCallbacks(poll);
        // Avoid continuous idle polling and preserve card views/TalkBack focus while health changes.
        if (started && busy) main.postDelayed(poll, 500);
    }
    private static void text(TextView view, String value) { if (!value.contentEquals(view.getText())) view.setText(value); }
    private String known(String value) { return value.isEmpty() ? getString(R.string.feeds_unknown) : value; }
    private String time(long ms) { return ms == 0 ? getString(R.string.feeds_unknown) : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms)); }
    private String policy(FeedUpdate.Policy policy) { return getString(policy == FeedUpdate.Policy.RETAIN_BUNDLED ? R.string.feeds_retain : R.string.feeds_only); }
    private String category(FeedSource source) {
        return getString(switch (source.category) {
            case "adult" -> R.string.feeds_adult; case "gambling" -> R.string.feeds_gambling;
            case "ads" -> R.string.feeds_ads; default -> R.string.feeds_malware;
        });
    }
    private String code(FeedHealth.Code code, int http) {
        if (code == FeedHealth.Code.HTTP) return getString(R.string.feeds_http, http);
        return getString(switch (code) {
            case NEVER -> R.string.feeds_never; case RUNNING -> R.string.feeds_running; case OK -> R.string.feeds_ok;
            case NETWORK -> R.string.feeds_network; case TIMEOUT -> R.string.feeds_timeout; case UTF8 -> R.string.feeds_utf8;
            case PARSE -> R.string.feeds_parse; case SIZE -> R.string.feeds_size; case SHRINK -> R.string.feeds_shrink;
            case STORAGE -> R.string.feeds_storage; case METADATA -> R.string.feeds_metadata; case CANCELLED -> R.string.feeds_cancelled;
            default -> R.string.feeds_unknown;
        });
    }
}
