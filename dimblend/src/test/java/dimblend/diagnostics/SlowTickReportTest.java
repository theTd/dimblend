package dimblend.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlowTickReportTest {
    private static final long MS = 1_000_000L;

    private static Map<String, Long> tick() {
        return Map.of(
                "tick", 60 * MS,
                "tick/levels", 50 * MS,
                "tick/levels/rotating", 45 * MS,
                "tick/levels/rotating/entities", 25 * MS,
                "tick/levels/rotating/chunkSource", 15 * MS,
                "tick/levels/rotating/unspecified", 5 * MS,
                "tick/connection", 4 * MS,
                "nextTickWait", 8 * MS);
    }

    @Test
    void selfTimeSubtractsChildrenAndFoldsUnspecifiedIntoTheParent() {
        Map<String, Long> self = SlowTickReport.selfNanos(tick());
        // rotating: 45 - entities 25 - chunkSource 15 = 5 (its "unspecified" share stays with it)
        assertEquals(5 * MS, self.get("tick/levels/rotating"));
        assertEquals(5 * MS, self.get("tick/levels"));
        assertEquals(6 * MS, self.get("tick"));
        assertFalse(self.containsKey("tick/levels/rotating/unspecified"));
    }

    @Test
    void topSelfListsTheLargestOwnTimeFirst() {
        List<Map.Entry<String, Long>> top = SlowTickReport.topSelf(tick(), 2);
        assertEquals("tick/levels/rotating/entities", top.get(0).getKey());
        assertEquals("tick/levels/rotating/chunkSource", top.get(1).getKey());
        assertTrue(SlowTickReport.line(tick(), 60 * MS, 8 * MS).startsWith("60.0 ms work (+8.0 ms wait)"));
    }

    @Test
    void renderAveragesOverSlowTicksAndNestsSections() {
        SlowTickReport report = new SlowTickReport();
        report.add(tick(), 60 * MS, 8 * MS);
        report.add(tick(), 60 * MS, 8 * MS);
        String text = report.render(50, 400, 40, 0.1D);
        assertTrue(text.contains("400 ticks seen after a 40-tick warm-up, 2 slow"), text);
        assertTrue(text.contains("average slow tick: 60.0 ms work + 8.0 ms nextTickWait"), text);
        // Averages, not sums: entities is 25 ms per slow tick, nested under rotating.
        assertTrue(text.contains("        25.00  entities"), text);
        assertTrue(text.indexOf("rotating") < text.indexOf("entities"), text);
    }

    @Test
    void emptyReportOnlyHasTheHeader() {
        String text = new SlowTickReport().render(50, 10, 40, 0.1D);
        assertTrue(text.contains("0 slow"));
        assertFalse(text.contains("average slow tick"));
    }
}
