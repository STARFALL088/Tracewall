package tracewall.data;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Existing configured endpoints and attribution, not a remotely editable source registry. */
public final class FeedSource {
    public static final List<FeedSource> ALL = Collections.unmodifiableList(Arrays.asList(
        new FeedSource("adult", "https://raw.githubusercontent.com/tiuxo/hosts/master/porn", "Tiuxo hosts; CC BY 4.0"),
        new FeedSource("gambling", "https://raw.githubusercontent.com/bigdargon/hostsVN/master/extensions/gambling/hosts-VN", "hostsVN, BigDargon; MIT; see lists-licenses.txt"),
        new FeedSource("ads", "https://raw.githubusercontent.com/tiuxo/hosts/master/ads", "Tiuxo hosts; CC BY 4.0"),
        new FeedSource("malware", "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt", "urlhaus-filter, GPL-3.0; URLhaus data, CC0; see lists-licenses.txt")
    ));
    public final String category, url, attribution;
    private FeedSource(String category, String url, String attribution) {
        this.category = category; this.url = https(url); this.attribution = attribution;
    }
    public static FeedSource of(String category) {
        for (FeedSource source : ALL) if (source.category.equals(category)) return source;
        throw new IllegalArgumentException("Unknown feed category");
    }
    public static String https(String value) {
        if (value == null || value.length() > 512 || !safe(value, 512)) throw new IllegalArgumentException("Invalid feed URL");
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() < -1 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("HTTPS feed URL required");
            }
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid feed URL", e); }
        return value;
    }
    public static boolean safe(String value, int maximum) {
        if (value == null || value.length() > maximum) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }
    /** Optional HTTP header metadata must never become unbounded or a log/UI injection. */
    public static String header(String value) { return safe(value, 256) ? value : ""; }
}
