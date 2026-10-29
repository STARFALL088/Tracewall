package tracewall.data;

/** RFC 4180 field quoting for the history export. */
public final class CsvRow {
    private CsvRow() {}
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
