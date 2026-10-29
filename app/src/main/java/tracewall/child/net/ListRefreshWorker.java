package tracewall.child.net;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;
import tracewall.child.block.BlocklistStore;

/** Persisted, best-effort daily refresh. WorkManager's Worker runs off the main thread. */
public final class ListRefreshWorker extends Worker {
    public static final String WORK_NAME = "tracewall.category-refresh.v1";
    public ListRefreshWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }
    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ListRefreshWorker.class, 24, TimeUnit.HOURS)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(24, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
    @NonNull @Override public Result doWork() {
        if (!BlocklistStore.isEnabled(getApplicationContext())) return Result.success();
        ListUpdater.Result result = ListUpdater.updateBlocking(getApplicationContext(), (completed, total) ->
            setProgressAsync(new Data.Builder().putInt("completed", completed).putInt("total", total).build()));
        if (isStopped()) return Result.retry();
        return result.success() ? Result.success() : Result.retry();
    }
}
