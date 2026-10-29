package tracewall.data;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class CsvRowTest {
    @Test void quotesCommasNewlinesQuotesAndEmptyFields() {
        assertEquals("\"a,b\",\"a\"\"b\",\"line\nnext\",\"\",\"42\"\r\n",
            CsvRow.of("a,b", "a\"b", "line\nnext", null, 42));
    }
}
