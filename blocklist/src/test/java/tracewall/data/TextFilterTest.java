package tracewall.data;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TextFilterTest {
    @Test void searchesAllFieldsCaseInsensitivelyWithoutTreatingInputAsRegex() {
        assertTrue(TextFilter.matches("  EXAMPLE.COM ", "example.com", "Browser"));
        assertTrue(TextFilter.matches("browser", "safe.test", "My Browser"));
        assertTrue(TextFilter.matches("বাংলা", "বাংলা অ্যাপ"));
        assertFalse(TextFilter.matches(".*", "example.com"));
        assertFalse(TextFilter.matches("absent", "example.com", null));
        assertTrue(TextFilter.matches(" ", (String)null));
        assertTrue(TextFilter.matches(null, "example.com"));
    }
}
