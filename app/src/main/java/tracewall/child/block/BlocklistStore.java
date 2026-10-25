package tracewall.child.block;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tracewall.blocklist.DomainSet;
import tracewall.blocklist.HostListParser;
import tracewall.blocklist.PolicyEngine;

/**
 * The app's single source of truth for rules.
 *
 * A singleton because two things need the same engine in the same process: the
 * VPN service on the packet path, and the activity that lets the user allow a
 * host. Two independent copies mean an allowlist change silently not applying
 * until the tunnel restarts.
 *
 * The engine is swapped atomically on reload, so the packet loop never sees a
 * half-built policy and never takes a lock on the hot path.
 */
public final class BlocklistStore {

    private static final String TAG = "Tracewall";
    public static final String PREFS = "tracewall.rules";
    public static final String KEY_ALLOW = "allow";
    public static final String KEY_BLOCKED_UIDS = "blocked_uids";
    public static final String KEY_ENABLED = "enabled";
    /** Parent-pushed rules live under separate keys so they can be replaced
     *  atomically without disturbing rules the child set on-device. */
    public static final String KEY_REMOTE_ALLOW = "remote_allow";
    public static final String KEY_REMOTE_BLOCKED_UIDS = "remote_blocked_uids";

    private static final BlocklistStore INSTANCE = new BlocklistStore();

    public static BlocklistStore get() {
        return INSTANCE;
    }

    private Context app;
    private Set<String> lastCategories = Collections.emptySet();
    private volatile PolicyEngine engine = new PolicyEngine(null, null, null);
    private volatile String summary = "not loaded";

    private BlocklistStore() {}

    public PolicyEngine.Decision decide(String host, int uid) {
        return engine.decide(host, uid);
    }

    public int size() {
        return engine.size();
    }

    public String summary() {
        return summary;
    }

    /**
     * Build the policy from the selected category assets plus the user's own
     * rules. Each category is parsed on its own so that a block can report
     * which category caught it - that reason is what the UI shows.
     */
    public synchronized void load(Context ctx, Set<String> categories) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null) return;
        if (categories != null) lastCategories = new HashSet<>(categories);

        Map<String, DomainSet> perCategory = new LinkedHashMap<>();
        int malformed = 0;
        int duplicates = 0;
        for (String c : lastCategories) {
            String text = readAsset(app, "categories/" + c + ".txt");
            if (text == null) {
                Log.w(TAG, "missing category asset: " + c);
                continue;
            }
            HostListParser.Result r = HostListParser.parse(text);
            perCategory.put(c, r.set());
            malformed += r.malformed();
            duplicates += r.duplicates();
        }

        SharedPreferences sp = prefs(app);

        // Merge local rules (set on the child device) with remote rules
        // pushed by the parent. Both sources are honoured; the parent can
        // add allows and blocks without erasing anything the child set.
        Set<String> mergedAllow = new HashSet<>(
            sp.getStringSet(KEY_ALLOW, Collections.<String>emptySet())
        );
        mergedAllow.addAll(
            sp.getStringSet(KEY_REMOTE_ALLOW, Collections.<String>emptySet())
        );
        DomainSet allow = DomainSet.of(mergedAllow);

        Set<String> mergedUids = new HashSet<>(
            sp.getStringSet(KEY_BLOCKED_UIDS, Collections.<String>emptySet())
        );
        mergedUids.addAll(
            sp.getStringSet(
                KEY_REMOTE_BLOCKED_UIDS,
                Collections.<String>emptySet()
            )
        );
        Set<Integer> uids = parseUids(mergedUids);

        engine = new PolicyEngine(allow, perCategory, uids);
        summary =
            engine.summary() +
            " (" +
            malformed +
            " malformed, " +
            duplicates +
            " dupes)";
        Log.i(TAG, "policy loaded: " + summary);
    }

    public synchronized void reload() {
        if (app != null) load(app, lastCategories);
    }

    /** Long-press on a blocked row: allow this host through every category. */
    public synchronized void allow(Context ctx, String host) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null || host == null || host.isEmpty()) return;
        SharedPreferences sp = prefs(app);
        Set<String> next = new HashSet<>(
            sp.getStringSet(KEY_ALLOW, Collections.<String>emptySet())
        );
        next.add(host.toLowerCase());
        sp.edit().putStringSet(KEY_ALLOW, next).apply();
        reload();
    }

    public synchronized void blockApp(Context ctx, int uid) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null || uid < 0) return;
        SharedPreferences sp = prefs(app);
        Set<String> next = new HashSet<>(
            sp.getStringSet(KEY_BLOCKED_UIDS, Collections.<String>emptySet())
        );
        next.add(Integer.toString(uid));
        sp.edit().putStringSet(KEY_BLOCKED_UIDS, next).apply();
        reload();
    }

    /**
     * Replace the remote rule set pushed by the parent via Realtime Database.
     * Local rules (set on-device) are preserved under their own keys and
     * merged back in by {@link #load}.
     */
    public synchronized void applyRemoteRules(
        Context ctx,
        Set<String> allow,
        Set<String> blockedUidStrings
    ) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null) return;
        prefs(app)
            .edit()
            .putStringSet(
                KEY_REMOTE_ALLOW,
                allow != null ? allow : Collections.<String>emptySet()
            )
            .putStringSet(
                KEY_REMOTE_BLOCKED_UIDS,
                blockedUidStrings != null
                    ? blockedUidStrings
                    : Collections.<String>emptySet()
            )
            .apply();
        reload();
        Log.i(
            TAG,
            "remote rules applied: " +
                (allow != null ? allow.size() : 0) +
                " allow, " +
                (blockedUidStrings != null ? blockedUidStrings.size() : 0) +
                " blocked uids"
        );
    }

    /** The per-app undo: mirrors blockApp, same rule set, no restart either. */
    public synchronized void unblockApp(Context ctx, int uid) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null || uid < 0) return;
        SharedPreferences sp = prefs(app);
        Set<String> next = new HashSet<>(
            sp.getStringSet(KEY_BLOCKED_UIDS, Collections.<String>emptySet())
        );
        next.remove(Integer.toString(uid));
        sp.edit().putStringSet(KEY_BLOCKED_UIDS, next).apply();
        reload();
    }

    /** Switch state for the per-app screen, read from the live policy. */
    public boolean isAppBlocked(int uid) {
        return engine.isAppBlocked(uid);
    }

    // -------------------------------------------------------------- prefs

    /** Whether the user wants protection on, so a restart can honour it. */
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx
            .getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Set<Integer> parseUids(Set<String> raw) {
        Set<Integer> out = new HashSet<>();
        if (raw == null) return out;
        for (String s : raw) {
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited pref containing junk is not worth a crash
            }
        }
        return out;
    }

    private static String readAsset(Context ctx, String path) {
        try (
            BufferedReader r = new BufferedReader(
                new InputStreamReader(
                    ctx.getAssets().open(path),
                    StandardCharsets.UTF_8
                )
            )
        ) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
