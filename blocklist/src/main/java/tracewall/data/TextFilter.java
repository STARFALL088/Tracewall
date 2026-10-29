package tracewall.data;
import java.util.Locale;
/** Locale-independent search shared by the query and installed-app lists. */
public final class TextFilter {
    private TextFilter() {}
    public static boolean matches(String query, String... fields) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        for (String field : fields) if (field != null && field.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }
}
