package dimblend.radio.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioSimulationSelectionTest {
    private static final double RANGE = 96;

    private static Map<String, Double> distances(Object... pairs) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        return map;
    }

    @Test void nearestInRangeRadiosAreSimulated() {
        var selected = RadioSimulationSelection.select(
                distances("a", 10, "b", 20, "c", 30, "d", 40, "e", 50, "far", 100), Set.of(), RANGE);
        assertEquals(Set.of("a", "b", "c", "d"), selected);
        assertEquals(Set.of("a"), RadioSimulationSelection.select(distances("a", 10, "far", 100), Set.of(), RANGE));
    }

    @Test void aSelectedRadioKeepsItsSlotAgainstASlightlyCloserRival() {
        Set<String> previous = Set.of("a", "b", "c", "d");
        var kept = RadioSimulationSelection.select(distances("a", 10, "b", 20, "c", 30, "d", 40, "e", 37), previous, RANGE);
        assertEquals(previous, kept, "3 blocks closer is within the hysteresis");
        var swapped = RadioSimulationSelection.select(distances("a", 10, "b", 20, "c", 30, "d", 40, "e", 35), previous, RANGE);
        assertEquals(Set.of("a", "b", "c", "e"), swapped, "5 blocks closer takes the slot");
    }

    @Test void leavingTheRangeFreesTheSlot() {
        Set<String> previous = Set.of("a", "b", "c", "d");
        var selected = RadioSimulationSelection.select(distances("a", 10, "b", 20, "c", 30, "d", 97, "e", 60), previous, RANGE);
        assertEquals(Set.of("a", "b", "c", "e"), selected);
    }
}
