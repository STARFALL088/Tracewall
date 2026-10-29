package tracewall.data;

/** RFC 4180 field quoting for the history export. */
public final class CsvRow {
    private CsvRow() {}
    /** Opt-in spreadsheet-safe text; raw RFC 4180 serialization remains unchanged. */
    public static String spreadsheetText(String value) {
        if (value == null || value.isEmpty()) return value;
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@'
            || Character.isWhitespace(first) ? "'" + value : value;
    }
    public static String of(Object... fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) out.append(',');
            String value = fields[i] == null ? "" : fields[i].toString();
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return out.append("\r\n").toString();
    }
}
