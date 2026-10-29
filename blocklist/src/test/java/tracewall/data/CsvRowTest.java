package tracewall.data;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
class CsvRowTest {
    @Test void spreadsheetSafetyIsOptInAndRetainsTheOriginalText() {
        for (String value : new String[]{"=1+1", "+formula", "-formula", "@formula", "\tformula", " formula"})
            assertEquals("'" + value, CsvRow.spreadsheetText(value));
        assertEquals("example.com", CsvRow.spreadsheetText("example.com"));
        assertEquals("", CsvRow.spreadsheetText(""));
        assertNull(CsvRow.spreadsheetText(null));
        assertEquals("\"=1+1\"\r\n", CsvRow.of("=1+1"));
    }
    @Test void quotesCommasNewlinesQuotesAndEmptyFields() {
        assertEquals("\"a,b\",\"a\"\"b\",\"line\nnext\",\"\",\"42\"\r\n",
            CsvRow.of("a,b", "a\"b", "line\nnext", null, 42));
    }
}
