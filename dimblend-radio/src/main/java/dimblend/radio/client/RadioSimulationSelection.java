package dimblend.radio.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which in-range radios Steam Audio renders; the rest are stereo-panned. A selected
 * radio keeps its slot until a rival is {@link #HYSTERESIS} blocks closer, so two radios at
 * similar distances do not trade places (and acoustic paths) on every step.
 */
final class RadioSimulationSelection {
    static final int LIMIT = 4;
    static final double HYSTERESIS = 4;

    /**
     * @param distances listener distance per radio
     * @param previous radios selected last time
     * @param range only radios closer than this are eligible
     */
    static <T> Set<T> select(Map<T, Double> distances, Set<T> previous, double range) {
        List<Map.Entry<T, Double>> eligible = new ArrayList<>();
        for (Map.Entry<T, Double> entry : distances.entrySet()) {
            if (entry.getValue() < range) eligible.add(entry);
        }
        eligible.sort(Comparator.comparingDouble(entry -> entry.getValue() - (previous.contains(entry.getKey()) ? HYSTERESIS : 0)));
        Set<T> selected = new HashSet<>();
        for (int i = 0; i < eligible.size() && i < LIMIT; i++) selected.add(eligible.get(i).getKey());
        return selected;
    }

    private RadioSimulationSelection() { }
}
