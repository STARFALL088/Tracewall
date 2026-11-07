package tracewall.child.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import tracewall.child.block.BlocklistStore;
import tracewall.data.*;

/** Serialized bounded HTTPS refresh/settings. Never starts/stops a VPN or changes remote rules. */
public final class ListUpdater {
    // A caller reserves the gate before submitting: at most one operation, no accumulated taps/workers.
    private static final Object GATE = new Object();
    private static boolean running;
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), r -> new Thread(r, "tracewall-feeds"));
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1,
        r -> new Thread(r, "tracewall-feed-deadline"));
    static { WATCHDOG.setRemoveOnCancelPolicy(true); }
    private static final long SOURCE_BUDGET_MS = 45_000, RUN_BUDGET_MS = 180_000;
    private static final String POLICY = "feeds.retention.v1";
    public interface Listener {
        void progress(int completed, int total);
        void done(Result result);
    }
    public static final class Result {
        public final int updated, failed, added, removed;
        public final boolean busy, retryable;
        Result(int updated, int failed, int added) { this(updated, failed, added, 0, false, false); }
        Result(int updated, int failed, int added, int removed, boolean busy, boolean retryable) {
            this.updated = updated; this.failed = failed; this.added = added; this.removed = removed;
            this.busy = busy; this.retryable = retryable;
        }
        /** A partial refresh still replaced at least one list; only a total failure retries. */
        public boolean success() { return updated > 0; }
    }
    private ListUpdater() {}
    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("tracewall.list_updates", Context.MODE_PRIVATE);
    }
    public static boolean isRunning() { synchronized (GATE) { return running; } }
    private static boolean reserve() { synchronized (GATE) { if (running) return false; running = true; return true; } }
    private static void release() { synchronized (GATE) { running = false; } }
    public static FeedUpdate.Policy retentionPolicy(Context context) {
        try { return FeedUpdate.Policy.valueOf(prefs(context).getString(POLICY, FeedUpdate.Policy.RETAIN_BUNDLED.name())); }
        catch (RuntimeException e) { return FeedUpdate.Policy.RETAIN_BUNDLED; }
    }
    public static FeedHealth health(Context context, FeedSource source) {
        try {
            String json = prefs(context).getString("feeds.health." + source.category, null);
            return json == null ? FeedHealth.empty(source) : FeedHealth.decode(json, source);
        } catch (RuntimeException e) {
            return FeedHealth.empty(source).rejected(0, FeedHealth.Code.METADATA,
                new FeedHealth.Response(0, source.url, "", ""), null);
        }
    }
    private static boolean saveHealth(Context context, FeedSource source, FeedHealth health) {
        boolean ok = prefs(context).edit().putString("feeds.health." + source.category, health.encode()).commit();
        if (!ok) Log.w("Tracewall", "Cannot persist feed health: " + source.category);
        return ok;
    }
    /** Caller MUST invoke through PinGate.require, after explaining that this affects future successful refreshes. */
    public static void setRetentionAfterPin(Context context, FeedUpdate.Policy policy, Consumer<Boolean> done) {
        if (policy == null) throw new IllegalArgumentException("Missing retention policy");
        Context app = context.getApplicationContext();
        setting(() -> prefs(app).edit().putString(POLICY, policy.name()).commit()
            && retentionPolicy(app) == policy, done);
    }
    /** Approve only the exact most recently rejected content, never a blanket/next-download size bypass. */
    public static void approveShrinkAfterPin(Context context, FeedSource source, String digest, Consumer<Boolean> done) {
        Context app = context.getApplicationContext();
        setting(() -> {
            FeedHealth current = health(app, source);
            if (current.status() != FeedHealth.Code.SHRINK || !current.metadata("pendingDigest").equals(digest)) return false;
            String key = "feeds.approved." + source.category;
            return prefs(app).edit().putString(key, digest).commit() && digest.equals(prefs(app).getString(key, ""));
        }, done);
    }
    private static void setting(BooleanSupplier operation, Consumer<Boolean> done) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!reserve()) { main.post(() -> done.accept(false)); return; }
        EXECUTOR.execute(() -> {
            boolean ok = false;
            try { ok = operation.getAsBoolean(); }
            catch (RuntimeException e) { Log.w("Tracewall", "Feed setting failed", e); }
            finally { release(); }
            boolean result = ok;
            main.post(() -> done.accept(result));
        });
    }
    public static void update(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (!reserve()) { main.post(() -> listener.done(new Result(0, 0, 0, 0, true, false))); return; }
        EXECUTOR.execute(() -> {
            Result result;
            try { result = refresh(app, (completed, total) -> main.post(() -> listener.progress(completed, total)), () -> false); }
            finally { release(); }
            main.post(() -> listener.done(result));
        });
    }
    public interface Progress { void report(int completed, int total); }
    public static Result updateBlocking(Context context, Progress progress) { return updateBlocking(context, progress, () -> false); }
    /** Workers coalesce instead of waiting in an unbounded monitor/executor queue. */
    public static Result updateBlocking(Context context, Progress progress, BooleanSupplier cancelled) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("List update on main thread");
        if (!reserve()) return new Result(0, 0, 0, 0, true, false);
        try { return refresh(context.getApplicationContext(), progress, cancelled); }
        finally { release(); }
    }
    private static Result refresh(Context context, Progress progress, BooleanSupplier cancelled) {
        int updated = 0, failed = 0, added = 0, removed = 0, completed = 0;
        boolean retryable = false;
        long runDeadline = SystemClock.elapsedRealtime() + RUN_BUDGET_MS;
        FeedUpdate.Policy policy = retentionPolicy(context); // frozen for this run; setting shares the same gate
        File directory = new File(context.getFilesDir(), "lists");
        if (!prefs(context).edit().putLong("attempt_ms", System.currentTimeMillis()).commit()) Log.w("Tracewall", "Cannot persist aggregate feed attempt");
        for (FeedSource source : FeedSource.ALL) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= runDeadline) break;
            File temp = null;
            FeedHealth health = health(context, source).attempt(System.currentTimeMillis());
            Download attempt = new Download(source);
            FeedHealth.Code stage = FeedHealth.Code.STORAGE;
            long deadline = Math.min(runDeadline, SystemClock.elapsedRealtime() + SOURCE_BUDGET_MS);
            try {
                if (!saveHealth(context, source, health)) throw new IOException("Cannot persist attempt");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create list directory");
                // Fixed per-category staging name also bounds leftovers after process death.
                temp = new File(directory, source.category + ".pending");
                stage = FeedHealth.Code.NETWORK;
                attempt.fetch(temp, cancelled, deadline);
                stage = FeedHealth.Code.UTF8;
                String raw = read(new FileInputStream(temp));
                stage = FeedHealth.Code.PARSE;
                ListData.validate(raw, ListData.MIN_FEED_HOSTS);
                stage = FeedHealth.Code.STORAGE;
                String bundled = read(context.getAssets().open("categories/" + source.category + ".txt"));
                File destination = new File(directory, source.category + ".txt");
                String previous = destination.isFile() ? read(new FileInputStream(destination)) : bundled;
                stage = FeedHealth.Code.METADATA;
                FeedUpdate.snapshot(previous, source); // validate the authoritative, atomic cache header, not mutable UI stats
                String approvalKey = "feeds.approved." + source.category;
                String approval;
                try {
                    approval = prefs(context).getString(approvalKey, "");
                    if (approval == null || !approval.matches("[a-f0-9]{64}")) approval = "";
                } catch (RuntimeException invalid) { approval = ""; } // invalid local metadata never grants an override
                stage = FeedHealth.Code.PARSE;
                FeedUpdate.Candidate candidate = FeedUpdate.prepare(source, bundled, raw, previous, policy, approval);
                check(cancelled, deadline);
                stage = FeedHealth.Code.STORAGE;
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    output.write(ListData.bytes(candidate.text));
                    output.getFD().sync();
                }
                check(cancelled, deadline);
                // Same filesystem, atomic replacement; all HTTP/UTF-8/parse/size/shrink checks precede this point.
                android.system.Os.rename(temp.getAbsolutePath(), destination.getAbsolutePath());
                updated++; added += candidate.added; removed += candidate.removed;
                saveHealth(context, source, health.accepted(System.currentTimeMillis(), candidate, attempt.response));
                // Consume exact-content approval on success, even if this candidate no longer needed an override.
                if (!prefs(context).edit().remove(approvalKey).commit()) Log.w("Tracewall", "Cannot consume feed approval");
            } catch (IOException | IllegalArgumentException | android.system.ErrnoException e) {
                failed++;
                FeedUpdate.ShrinkException shrink = e instanceof FeedUpdate.ShrinkException ? (FeedUpdate.ShrinkException)e : null;
                FeedHealth.Code code = shrink != null ? FeedHealth.Code.SHRINK
                    : e instanceof FetchException ? ((FetchException)e).code
                    : e instanceof ListData.SizeException || e instanceof ListData.TooLargeException ? FeedHealth.Code.SIZE
                    : e instanceof CharacterCodingException ? FeedHealth.Code.UTF8
                    : e instanceof InterruptedIOException ? (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() ? FeedHealth.Code.CANCELLED : FeedHealth.Code.TIMEOUT)
                    : stage;
                retryable |= code == FeedHealth.Code.NETWORK || code == FeedHealth.Code.TIMEOUT
                    || (code == FeedHealth.Code.HTTP && (attempt.response.http == 429 || attempt.response.http >= 500));
                saveHealth(context, source, health.rejected(System.currentTimeMillis(), code, attempt.response, shrink));
                // Never log response bodies, URLs with redirected query strings, or untrusted header text.
                Log.w("Tracewall", "List refresh failed: " + source.category + ": " + code);
                if (code == FeedHealth.Code.SHRINK && !prefs(context).edit().remove("feeds.approved." + source.category).commit()) Log.w("Tracewall", "Cannot invalidate feed approval");
            } finally {
                if (temp != null && temp.exists() && !temp.delete()) Log.w("Tracewall", "Cannot remove list temp file");
            }
            if (progress != null) progress.report(++completed, FeedSource.ALL.size());
        }
        if (updated > 0) BlocklistStore.get().reload(); // swaps rules in place; never controls VPN lifecycle
        boolean incomplete = completed != FeedSource.ALL.size();
        SharedPreferences.Editor editor = prefs(context).edit().putInt("updated", updated)
            .putInt("failed", failed + (incomplete ? 1 : 0)).putInt("added", added).putInt("removed", removed);
        // A refresh where at least one list was replaced is a success even
        // if another source is down; otherwise one dead feed would pin the
        // UI to "Last successful update: Never" forever. Per-feed health still
        // makes a partial refresh and each failure visible.
        if (updated > 0) editor.putLong("success_ms", System.currentTimeMillis());
        if (!editor.commit()) Log.w("Tracewall", "Cannot persist aggregate feed result");
        return new Result(updated, failed + (incomplete ? 1 : 0), added, removed, false, retryable);
    }
    private static String read(InputStream input) throws IOException {
        try (InputStream in = input) { return ListData.utf8(ListData.read(in, ListData.MAX_BYTES)); }
    }
    private static void check(BooleanSupplier cancelled, long deadline) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
        if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException("Feed deadline");
    }
    private static final class FetchException extends IOException {
        final FeedHealth.Code code;
        FetchException(FeedHealth.Code code) { super(code.name()); this.code = code; }
    }
    private static final class Download {
        FeedHealth.Response response;
        Download(FeedSource source) { response = new FeedHealth.Response(0, source.url, "", ""); }
        void fetch(File target, BooleanSupplier cancelled, long deadline) throws IOException {
            URL url = new URL(response.url);
            for (int redirects = 0; redirects <= 3; redirects++) {
                check(cancelled, deadline);
                FeedSource.https(url.toString());
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                long remaining = deadline - SystemClock.elapsedRealtime();
                connection.setConnectTimeout((int)Math.max(1, Math.min(15_000, remaining)));
                connection.setReadTimeout((int)Math.max(1, Math.min(20_000, remaining)));
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                AtomicBoolean expired = new AtomicBoolean();
                // Disconnect a trickle-download too: readTimeout alone is only an idle timeout.
                ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
                    if (cancelled.getAsBoolean() || SystemClock.elapsedRealtime() >= deadline) {
                        expired.set(true); connection.disconnect();
                    }
                }, 200, 200, TimeUnit.MILLISECONDS);
                try {
                    response = new FeedHealth.Response(0, url.toString(), "", "");
                    int status = connection.getResponseCode();
                    response = new FeedHealth.Response(status, url.toString(), FeedSource.header(connection.getHeaderField("ETag")),
                        FeedSource.header(connection.getHeaderField("Last-Modified")));
                    check(cancelled, deadline);
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || !FeedSource.safe(location, 512)) throw new FetchException(FeedHealth.Code.NETWORK);
                        url = new URL(url, location);
                        continue;
                    }
                    if (status != 200) throw new FetchException(FeedHealth.Code.HTTP);
                    if (connection.getContentLengthLong() > ListData.MAX_BYTES) throw new ListData.SizeException();
                    String encoding = connection.getContentEncoding();
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new FetchException(FeedHealth.Code.NETWORK);
                    long length = connection.getContentLengthLong();
                    try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int count, total = 0;
                        while ((count = input.read(buffer)) != -1) {
                            check(cancelled, deadline);
                            if (count > ListData.MAX_BYTES - total) throw new ListData.SizeException();
                            output.write(buffer, 0, count); total += count;
                        }
                        if (length >= 0 && total != length) throw new FetchException(FeedHealth.Code.NETWORK);
                    }
                    check(cancelled, deadline);
                    return;
                } catch (IOException e) {
                    if (expired.get()) {
                        check(cancelled, deadline);
                        throw new SocketTimeoutException("Feed deadline");
                    }
                    throw e;
                } finally { watchdog.cancel(false); connection.disconnect(); }
            }
            throw new FetchException(FeedHealth.Code.NETWORK);
        }
    }
}
