package tracewall.data;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ListDataTest {
    @Test void capsStreamsEvenWithoutContentLengthAndRejectsBrokenUtf8() throws Exception {
        assertArrayEquals(new byte[5], ListData.read(new ByteArrayInputStream(new byte[5]), 5));
        assertThrows(IOException.class, () -> ListData.read(new ByteArrayInputStream(new byte[6]), 5));
        assertThrows(IOException.class, () -> ListData.utf8(new byte[] {(byte)0xff}));
    }
    @Test void allBundledCategoriesAreValidBaselinesAndStayWithinBudget() throws Exception {
        long bytes = 0;
        for (String category : RulesJson.CATEGORIES) {
            java.nio.file.Path file = java.nio.file.Path.of("../app/src/main/assets/categories/" + category + ".txt");
            bytes += java.nio.file.Files.size(file);
            assertFalse(ListData.validate(java.nio.file.Files.readString(file), 100).isEmpty(), category);
        }
        assertTrue(bytes < ListData.MAX_BYTES);
    }
    @Test void rejectsMalformedOrSuspiciouslyShortDownloads() {
        assertThrows(IllegalArgumentException.class, () -> ListData.validate("<html>oops</html>", 1));
        assertThrows(IllegalArgumentException.class, () -> ListData.validate("one.test", 100));
    }
    @Test void mergesBaselineAndRefreshAndCountsOnlyNewDomains() {
        StringBuilder download = new StringBuilder();
        for (int i = 0; i < 100; i++) download.append("0.0.0.0 host").append(i).append(".test\n");
        download.append("127.0.0.1 host0.test # duplicate\n");
        String merged = ListData.merge("host0.test\nseed.test", download.toString(), "source/license");
        assertEquals(101, ListData.validate(merged, 100).size());
        assertEquals(99, ListData.added("host0.test\nseed.test", merged));
    }
}
