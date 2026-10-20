package tracewall.child.block;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tracewall.blocklist.DomainSet;
import tracewall.blocklist.HostListParser;

/**
 * Holds the active rule set and answers "is this host blocked".
 *
 * The set is swapped atomically on reload, so the packet loop never sees a
 * half-built list and never takes a lock on the hot path.
 */
public final class BlocklistStore {

    private static final String TAG = "Tracewall";

    private volatile DomainSet set = new DomainSet();
    private volatile int domains;
    private volatile int lists;
    private volatile String summary = "not loaded";

    public boolean blocks(String host) {
        return host != null && set.matches(host);
    }

    public int size() { return domains; }

    public String summary() { return summary; }

    /**
     * Load every selected category asset, plus any user rules, then parse the
     * lot in one pass. One pass matters: a hosts line can appear in two
     * categories, and the parser counts duplicates rather than silently
     * double-adding them.
     */
    public void load(Context ctx, Set<String> categories, List<String> userRules) {
        StringBuilder all = new StringBuilder(1 << 16);
        List<String> loaded = new ArrayList<>();
        if (categories != null) {
            for (String c : categories) {
                String text = readAsset(ctx, "categories/" + c + ".txt");
                if (text == null) {
                    Log.w(TAG, "missing category asset: " + c);
                    continue;
                }
                all.append(text).append('\n');
                loaded.add(c);
            }
        }
        if (userRules != null) {
            for (String rule : userRules) all.append(rule).append('\n');
        }

        HostListParser.Result r = HostListParser.parse(all.toString());
        set = r.set();
        domains = r.entriesParsed();
        lists = loaded.size();
        summary = lists + " lists / " + domains + " rules / "
                + r.malformed() + " malformed / " + r.duplicates() + " dupes";
        Log.i(TAG, "blocklist loaded: " + summary + " from " + loaded);
    }

    private static String readAsset(Context ctx, String path) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(path), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
