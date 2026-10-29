package tracewall.data;

import java.util.HashSet;
import java.util.Set;

/** Strict, depth-bounded preflight for the differing Android/JVM org.json parsers. */
final class BoundedRulesDocument {
    private static final int MAX_DEPTH = 8;
    private final String text;
    private int at;

    private BoundedRulesDocument(String text) { this.text = text; }

    static void validate(String text) {
        BoundedRulesDocument parser = new BoundedRulesDocument(text);
        parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw invalid();
    }

    private void value(int depth) {
        space();
        if (at >= text.length() || depth > MAX_DEPTH) throw invalid();
        char c = text.charAt(at);
        if (c == '{') object(depth + 1);
        else if (c == '[') array(depth + 1);
        else if (c == '"') string();
        else if (c == 't') literal("true");
        else if (c == 'f') literal("false");
        else if (c == 'n') literal("null");
        else number();
    }

    private void object(int depth) {
        at++;
        space();
        if (take('}')) return;
        Set<String> keys = new HashSet<>();
        do {
            space();
            String key = string();
            if (!keys.add(key) || keys.size() > RulesJson.MAX_ENTRIES) throw invalid();
            space();
            if (!take(':')) throw invalid();
            value(depth);
            space();
            if (take('}')) return;
        } while (take(','));
        throw invalid();
    }

    private void array(int depth) {
        at++;
        space();
        if (take(']')) return;
        int entries = 0;
        do {
            if (++entries > RulesJson.MAX_ENTRIES) throw invalid();
            value(depth);
            space();
            if (take(']')) return;
        } while (take(','));
        throw invalid();
    }

    private String string() {
        if (!take('"')) throw invalid();
        StringBuilder value = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return value.toString();
            if (c < 0x20) throw invalid();
            if (c == '\\') {
                if (at == text.length()) throw invalid();
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"': case '\\': case '/': c = escaped; break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        if (at > text.length() - 4) throw invalid();
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(at++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw invalid();
                            code = code * 16 + digit;
                        }
                        c = (char) code;
                        break;
                    default: throw invalid();
                }
            }
            value.append(c);
        }
        throw invalid();
    }

    private void number() {
        take('-');
        if (!take('0')) digits();
        if (take('.')) digits();
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            digits();
        }
    }

    private void digits() {
        int start = at;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
        if (start == at) throw invalid();
    }

    private void literal(String literal) {
        if (!text.startsWith(literal, at)) throw invalid();
        at += literal.length();
    }

    private boolean take(char c) {
        if (at < text.length() && text.charAt(at) == c) { at++; return true; }
        return false;
    }

    private void space() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
            at++;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid, duplicate-key or excessively nested rules JSON");
    }
}
