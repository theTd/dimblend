package dimblend.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregated vanilla profiler sections over the recorded slow ticks. Paths use {@code /}
 * between sections and start below the profiler root (e.g. {@code tick/levels/...}).
 * Pure: no Minecraft types, so the aggregation and rendering are unit-tested.
 */
public final class SlowTickReport {
    /** Leftover time the profiler could not attribute to a child section. */
    public static final String UNSPECIFIED = "unspecified";
    private final Map<String, Long> totalNanos = new HashMap<>();
    private long slowTicks;
    private long workNanos;
    private long waitNanos;
    private long worstWorkNanos;

    /** Adds one slow tick: its per-path durations, its work ({@code tick}) and its {@code nextTickWait}. */
    public void add(Map<String, Long> pathNanos, long tickWorkNanos, long tickWaitNanos) {
        slowTicks++;
        workNanos += tickWorkNanos;
        waitNanos += tickWaitNanos;
        worstWorkNanos = Math.max(worstWorkNanos, tickWorkNanos);
        pathNanos.forEach((path, nanos) -> totalNanos.merge(path, nanos, Long::sum));
    }

    public long slowTicks() { return slowTicks; }
    public long worstWorkNanos() { return worstWorkNanos; }

    /** Time spent in a path itself, i.e. not inside any recorded child section. */
    static Map<String, Long> selfNanos(Map<String, Long> pathNanos) {
        Map<String, Long> self = new HashMap<>(pathNanos);
        for (Map.Entry<String, Long> entry : pathNanos.entrySet()) {
            String path = entry.getKey();
            int cut = path.lastIndexOf('/');
            if (cut < 0 || path.endsWith("/" + UNSPECIFIED)) {
                continue;
            }
            self.computeIfPresent(path.substring(0, cut), (parent, nanos) -> nanos - entry.getValue());
        }
        // An "unspecified" child is the parent's own time, not a section of its own.
        self.keySet().removeIf(path -> path.endsWith("/" + UNSPECIFIED));
        return self;
    }

    /** The {@code limit} paths with the most self time, largest first. */
    static List<Map.Entry<String, Long>> topSelf(Map<String, Long> pathNanos, int limit) {
        return selfNanos(pathNanos).entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .toList();
    }

    /** One-line summary for a single slow tick. */
    public static String line(Map<String, Long> pathNanos, long tickWorkNanos, long tickWaitNanos) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%.1f ms work (+%.1f ms wait); most self time:",
                ms(tickWorkNanos), ms(tickWaitNanos)));
        for (Map.Entry<String, Long> entry : topSelf(pathNanos, 5)) {
            text.append(String.format(Locale.ROOT, " %s %.1f;", entry.getKey(), ms(entry.getValue())));
        }
        return text.toString();
    }

    /** Full summary: averages per slow tick as a tree, then the largest self-time sections. */
    public String render(long thresholdMs, long ticksSeen, int warmupTicks, double minAverageMs) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "dimblend slow-tick report: threshold %d ms (tick work), %d ticks seen after a %d-tick warm-up, %d slow%n",
                thresholdMs, ticksSeen, warmupTicks, slowTicks));
        if (slowTicks == 0) {
            return out.toString();
        }
        out.append(String.format(Locale.ROOT, "average slow tick: %.1f ms work + %.1f ms nextTickWait; worst %.1f ms work%n%n",
                ms(workNanos) / slowTicks, ms(waitNanos) / slowTicks, ms(worstWorkNanos)));
        out.append("Average ms per slow tick by section:\n");
        Map<String, List<String>> children = new HashMap<>();
        for (String path : totalNanos.keySet()) {
            int cut = path.lastIndexOf('/');
            children.computeIfAbsent(cut < 0 ? "" : path.substring(0, cut), parent -> new ArrayList<>()).add(path);
        }
        renderChildren(out, "", children, 0, minAverageMs);
        out.append("\nLargest self time (average ms per slow tick):\n");
        for (Map.Entry<String, Long> entry : topSelf(totalNanos, 25)) {
            out.append(String.format(Locale.ROOT, "  %7.2f  %s%n", ms(entry.getValue()) / slowTicks, entry.getKey()));
        }
        return out.toString();
    }

    private void renderChildren(StringBuilder out, String parent, Map<String, List<String>> children, int depth,
                                double minAverageMs) {
        List<String> kids = new ArrayList<>(children.getOrDefault(parent, List.of()));
        kids.sort(Comparator.comparingLong((String path) -> totalNanos.get(path)).reversed());
        for (String path : kids) {
            double average = ms(totalNanos.get(path)) / slowTicks;
            if (average < minAverageMs) {
                continue;
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            out.append("  ".repeat(depth + 1)).append(String.format(Locale.ROOT, "%7.2f  %s%n", average, name));
            renderChildren(out, path, children, depth + 1, minAverageMs);
        }
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0D;
    }
}
