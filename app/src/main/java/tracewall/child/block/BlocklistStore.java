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
import java.util.TreeMap;
import java.util.TreeSet;
import tracewall.blocklist.ScheduledProfile;
import tracewall.data.LocalRuleEdits;
import tracewall.data.RulesJson;
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
    public static final String KEY_LOCAL_CATEGORIES = "local_categories";
    /** New local fields are versioned separately; legacy keys remain authoritative. */
    public static final String KEY_LOCAL_DETAILS = "local_details_v2";
    public static final String KEY_ENABLED = "enabled";
    /** Parent-pushed rules live under separate keys so they can be replaced
     *  atomically without disturbing rules the child set on-device. */
    public static final String KEY_REMOTE_ALLOW = "remote_allow";
    public static final String KEY_REMOTE_BLOCKED_UIDS = "remote_blocked_uids";
    /** Active category names pushed by the parent; null = use device default. */
    public static final String KEY_REMOTE_CATEGORIES = "remote_categories";

    private static final BlocklistStore INSTANCE = new BlocklistStore();

    public static BlocklistStore get() {
        return INSTANCE;
    }

    private Context app;
    private Set<String> lastCategories = Collections.emptySet();
    private volatile PolicyEngine engine = new PolicyEngine(null, null, null);
    private volatile String summary = "not loaded";
    private boolean policyLoaded;

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
        long started = android.os.SystemClock.elapsedRealtime();
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null) return;
        if (categories != null) lastCategories = new HashSet<>(categories);

        SharedPreferences sp = prefs(app);
        RulesJson local;
        try { local = localDetails(sp); }
        catch (IllegalArgumentException e) {
            // Preserve a live policy. On cold start, still load legacy/base protection
            // rather than leaving the initial empty engine active because v2 is corrupt.
            Log.e(TAG, "Invalid local rule details; retaining loaded policy or cold-start base rules", e);
            if (policyLoaded) return;
            local = new RulesJson(Collections.emptySet(), Collections.emptySet(), localToggles(sp));
        }
        Set<String> remoteCats = sp.getStringSet(KEY_REMOTE_CATEGORIES, null);
        Set<String> activeCategories = remoteCats != null ? remoteCats : sp.getStringSet(KEY_LOCAL_CATEGORIES,
                lastCategories.isEmpty() ? RulesJson.CATEGORIES : lastCategories);
        Set<String> needed = new TreeSet<>(activeCategories);
        for (ScheduledProfile profile : local.profiles) needed.addAll(profile.categories);
        Map<String, DomainSet> allCategories = new LinkedHashMap<>();
        Map<String, DomainSet> perCategory = new LinkedHashMap<>();
        Map<String, DomainSet> managedCategories = new LinkedHashMap<>();
        int malformed = 0;
        int duplicates = 0;
        // Preload every referenced list once. Activating a schedule never reads disk.
        for (String c : needed) {
            String text = readCategory(app, c);
            if (text == null) {
                Log.w(TAG, "missing category asset: " + c);
                continue;
            }
            HostListParser.Result r = HostListParser.parse(text);
            allCategories.put(c, r.set());
            if (activeCategories.contains(c)) perCategory.put(c, r.set());
            if (remoteCats != null && remoteCats.contains(c)) managedCategories.put(c, r.set());
            malformed += r.malformed();
            duplicates += r.duplicates();
        }
        Set<String> remoteAllow = sp.getStringSet(KEY_REMOTE_ALLOW, Collections.emptySet());
        Set<Integer> remoteUids = parseUids(sp.getStringSet(KEY_REMOTE_BLOCKED_UIDS, Collections.emptySet()));
        // Preserve the legacy live-policy path, even if an old allowlist has an
        // unsupported query name. A malformed old allow must not disable all
        // category filtering on a cold start just because backup validation fails.
        Set<String> mergedAllow = new HashSet<>(sp.getStringSet(KEY_ALLOW, Collections.emptySet()));
        mergedAllow.addAll(remoteAllow);
        Set<Integer> uids = parseUids(sp.getStringSet(KEY_BLOCKED_UIDS, Collections.emptySet()));
        uids.addAll(remoteUids);
        PolicyEngine managed = new PolicyEngine(DomainSet.of(remoteAllow), managedCategories, remoteUids);
        // Parent allows stay authoritative; local imports/exceptions cannot weaken parent blocks.
        engine = new PolicyEngine(DomainSet.of(mergedAllow), perCategory, uids, DomainSet.of(local.deny),
                local.temporaryAllows, allCategories, local.profiles, managed);
        policyLoaded = true;
        summary =
            engine.summary() +
            " (" +
            malformed +
            " malformed, " +
            duplicates +
            " dupes)";
        Log.i(TAG, "policy loaded in " + (android.os.SystemClock.elapsedRealtime() - started) + " ms: " + summary);
    }

    public synchronized void reload() {
        if (app != null) load(app, lastCategories);
    }

    /** Permanent exception. False rejects unsupported query names/limits without crashing legacy callers. */
    public synchronized boolean allow(Context ctx, String host) {
        try {
            importLocal(ctx, LocalRuleEdits.permanentAllow(exportLocal(ctx), host));
            return true;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Local allow rejected by validation");
            return false;
        }
    }

    public Set<String> localAllows(Context ctx) {
        return new TreeSet<>(prefs(ctx).getStringSet(KEY_ALLOW, Collections.emptySet()));
    }

    public synchronized void removeAllow(Context ctx, String host) {
        // Keep the legacy cleanup path usable for unsupported old allow entries,
        // which cannot themselves pass the current backup/editor validator.
        Set<String> allows = localAllows(ctx);
        allows.remove(host);
        try { allows.remove(RulesJson.normalizePattern(host)); }
        catch (IllegalArgumentException ignored) { /* Exact legacy entry was removed above. */ }
        prefs(ctx).edit().putStringSet(KEY_ALLOW, allows).apply();
        app = ctx.getApplicationContext();
        reload();
    }

    /** Replaces an identical permanent exception, so a duration cannot remain permanent by accident. */
    public synchronized void allowForMinutes(Context ctx, String host, int minutes, long nowMillis) {
        importLocal(ctx, LocalRuleEdits.temporaryAllow(exportLocal(ctx), host, minutes, nowMillis));
    }

    public synchronized void removeTemporaryAllow(Context ctx, String host) {
        importLocal(ctx, LocalRuleEdits.removeAllow(exportLocal(ctx), host, true));
    }

    public synchronized void setDeny(Context ctx, String host, boolean blocked) {
        importLocal(ctx, LocalRuleEdits.deny(exportLocal(ctx), host, blocked));
    }

    /** originalName=null adds; updated=null removes. UI gates all profile edits with the PIN. */
    public synchronized void saveProfile(Context ctx, String originalName, ScheduledProfile updated) {
        importLocal(ctx, LocalRuleEdits.profile(exportLocal(ctx), originalName, updated));
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
     *
     * @param categories active category names, or {@code null} to keep the
     *                   on-device default (malware, gambling, adult, ads).
     */
    public synchronized void applyRemoteRules(
        Context ctx,
        Set<String> allow,
        Set<String> blockedUidStrings,
        Set<String> categories
    ) {
        if (ctx != null) app = ctx.getApplicationContext();
        if (app == null) return;
        SharedPreferences.Editor edit = prefs(app)
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
            );
        if (categories != null) {
            edit.putStringSet(KEY_REMOTE_CATEGORIES, categories);
        } else {
            edit.remove(KEY_REMOTE_CATEGORIES);
        }
        edit.apply();
        reload();
        Log.i(
            TAG,
            "remote rules applied: " +
                (allow != null ? allow.size() : 0) +
                " allow, " +
                (blockedUidStrings != null ? blockedUidStrings.size() : 0) +
                " blocked uids, categories=" +
                (categories != null ? categories : "default")
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
        if (enabled) tracewall.child.net.ListRefreshWorker.schedule(ctx);
        else tracewall.child.net.ListRefreshWorker.cancel(ctx);
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
                int uid = Integer.parseInt(s.trim());
                if (uid >= 0) out.add(uid);
            } catch (NumberFormatException ignored) {
                // a hand-edited pref containing junk is not worth a crash
            }
        }
        return out;
    }

    public synchronized RulesJson exportLocal(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        RulesJson extra = localDetails(sp);
        // Old callers still modify the original keys. Only new fields come from the detail document.
        return new RulesJson(localAllows(ctx), parseUids(sp.getStringSet(KEY_BLOCKED_UIDS, Collections.emptySet())),
                localToggles(sp), extra.deny, extra.temporaryAllows, extra.profiles);
    }

    private static Map<String, Boolean> localToggles(SharedPreferences sp) {
        Map<String, Boolean> toggles = new TreeMap<>();
        Set<String> active = sp.getStringSet(KEY_LOCAL_CATEGORIES, RulesJson.CATEGORIES);
        for (String category : RulesJson.CATEGORIES) toggles.put(category, active.contains(category));
        return toggles;
    }

    private static RulesJson localDetails(SharedPreferences sp) {
        String details = sp.getString(KEY_LOCAL_DETAILS, null);
        return details == null ? new RulesJson(Collections.emptySet(), Collections.emptySet(), localToggles(sp))
                : RulesJson.parse(details);
    }

    /** Storage seam, not an authorization boundary: UI/import callers must verify the parent PIN. */
    public synchronized void importLocal(Context ctx, RulesJson rules) {
        if (rules == null) throw new IllegalArgumentException("Rules required");
        String details = rules.serialize(); // Validate the total byte budget before any state changes.
        Set<String> uids = new HashSet<>(), active = new HashSet<>();
        for (int uid : rules.blockedUids) uids.add(Integer.toString(uid));
        for (Map.Entry<String, Boolean> entry : rules.categories.entrySet()) if (entry.getValue()) active.add(entry.getKey());
        // No remote, enabled, PIN or pairing keys are touched by a local backup.
        prefs(ctx).edit().putStringSet(KEY_ALLOW, rules.allow).putStringSet(KEY_BLOCKED_UIDS, uids)
                .putStringSet(KEY_LOCAL_CATEGORIES, active).putString(KEY_LOCAL_DETAILS, details).apply();
        app = ctx.getApplicationContext();
        reload();
    }

    private static String readCategory(Context ctx, String category) {
        if (!tracewall.data.RulesJson.CATEGORIES.contains(category)) return null;
        java.io.File downloaded = new java.io.File(ctx.getFilesDir(), "lists/" + category + ".txt");
        if (downloaded.isFile()) {
            try (java.io.InputStream input = new java.io.FileInputStream(downloaded)) {
                String text = tracewall.data.ListData.utf8(tracewall.data.ListData.read(input, tracewall.data.ListData.MAX_BYTES));
                tracewall.data.ListData.validate(text, 100);
                return text;
            } catch (IOException | IllegalArgumentException e) { Log.w(TAG, "Invalid downloaded list; using asset", e); }
        }
        return readAsset(ctx, "categories/" + category + ".txt");
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
