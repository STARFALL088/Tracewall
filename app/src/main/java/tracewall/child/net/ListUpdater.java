package tracewall.child.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import tracewall.child.block.BlocklistStore;
import tracewall.data.ListData;

/** HTTPS refresh shared by the explicit button and background work. Never touches the tunnel. */
public final class ListUpdater {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object UPDATE_LOCK = new Object();
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put("adult", "https://raw.githubusercontent.com/tiuxo/hosts/master/porn");
        SOURCES.put("gambling", "https://raw.githubusercontent.com/bigdargon/hostsVN/master/extensions/gambling/hosts-VN");
        SOURCES.put("ads", "https://raw.githubusercontent.com/tiuxo/hosts/master/ads");
        SOURCES.put("malware", "https://urlhaus.abuse.ch/downloads/hostfile/");
    }
    public interface Listener {
        void progress(int completed, int total);
        void done(Result result);
    }
    public static final class Result {
        public final int updated, failed, added;
        Result(int updated, int failed, int added) { this.updated = updated; this.failed = failed; this.added = added; }
        public boolean success() { return failed == 0; }
    }
    private ListUpdater() {}
    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("tracewall.list_updates", Context.MODE_PRIVATE);
    }
    public static void update(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(() -> {
            Result result = updateBlocking(app, (completed, total) -> main.post(() -> listener.progress(completed, total)));
            main.post(() -> listener.done(result));
        });
    }
    public interface Progress { void report(int completed, int total); }
    /** For a Worker thread; concurrent manual/periodic refreshes serialize here. */
    public static Result updateBlocking(Context context, Progress progress) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("List update on main thread");
        synchronized (UPDATE_LOCK) {
            int updated = 0, failed = 0, added = 0, completed = 0;
            File directory = new File(context.getFilesDir(), "lists");
            for (Map.Entry<String, String> entry : SOURCES.entrySet()) {
                File temp = null;
                try {
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create list directory");
                    temp = File.createTempFile(entry.getKey(), ".tmp", directory);
                    download(new URL(entry.getValue()), temp);
                    String raw = read(new FileInputStream(temp));
                    ListData.validate(raw, 100);
                    String bundled = read(context.getAssets().open("categories/" + entry.getKey() + ".txt"));
                    File destination = new File(directory, entry.getKey() + ".txt");
                    String previous = destination.isFile() ? read(new FileInputStream(destination)) : bundled;
                    String license = entry.getKey().equals("gambling") ? "hostsVN, BigDargon, MIT; see lists-licenses.txt"
                        : entry.getKey().equals("malware") ? "abuse.ch URLhaus, CC0"
                        : "Tiuxo hosts, CC BY 4.0; https://github.com/tiuxo/hosts/blob/master/LICENSE";
                    String merged = ListData.merge(bundled, raw, entry.getValue() + "; " + license);
                    int delta = ListData.added(previous, merged);
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        output.write(merged.getBytes(StandardCharsets.UTF_8));
                        output.getFD().sync();
                    }
                    // Same filesystem, atomic replacement. Failure preserves the last complete file.
                    android.system.Os.rename(temp.getAbsolutePath(), destination.getAbsolutePath());
                    added += delta;
                    updated++;
                } catch (IOException | IllegalArgumentException | android.system.ErrnoException e) {
                    failed++;
                    Log.w("Tracewall", "List refresh failed: " + entry.getKey(), e);
                } finally {
                    if (temp != null && temp.exists() && !temp.delete()) Log.w("Tracewall", "Cannot remove list temp file");
                }
                if (progress != null) progress.report(++completed, SOURCES.size());
            }
            if (updated > 0) BlocklistStore.get().reload();
            SharedPreferences.Editor editor = prefs(context).edit()
                .putLong("attempt_ms", System.currentTimeMillis()).putInt("updated", updated)
                .putInt("failed", failed).putInt("added", added);
            if (failed == 0) editor.putLong("success_ms", System.currentTimeMillis());
            editor.apply();
            return new Result(updated, failed, added);
        }
    }
    private static String read(InputStream input) throws IOException {
        try (InputStream in = input) { return ListData.utf8(ListData.read(in, ListData.MAX_BYTES)); }
    }
    private static void download(URL initial, File target) throws IOException {
        URL url = initial;
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) throw new IOException("HTTPS required");
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect location");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("HTTP " + status);
                if (connection.getContentLengthLong() > ListData.MAX_BYTES) throw new IOException("List exceeds 5 MB");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                    ListData.copy(input, output, ListData.MAX_BYTES);
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }
}
