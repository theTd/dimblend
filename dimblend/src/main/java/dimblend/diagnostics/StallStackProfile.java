package dimblend.diagnostics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stack samples of the server thread taken while one tick was stalled. Pure aggregation
 * (no Minecraft types): which frames were on the stack in how many samples, and which full
 * stacks repeated most.
 */
public final class StallStackProfile {
    private static final int STACK_KEY_FRAMES = 60;
    private final Map<String, Integer> framesInclusive = new HashMap<>();
    private final Map<List<String>, Integer> stacks = new HashMap<>();
    private int samples;

    public void add(StackTraceElement[] stack) {
        if (stack.length == 0) {
            return;
        }
        samples++;
        Set<String> seen = new HashSet<>();
        for (StackTraceElement element : stack) {
            String frame = frame(element);
            if (seen.add(frame)) {
                framesInclusive.merge(frame, 1, Integer::sum);
            }
        }
        List<String> key = java.util.Arrays.stream(stack).limit(STACK_KEY_FRAMES).map(StallStackProfile::frame).toList();
        stacks.merge(key, 1, Integer::sum);
    }

    public int samples() { return samples; }

    /** The deepest frame that sits on at least {@code minShare} of the samples (the hot spot). */
    public String hottestLeaf(double minShare) {
        // Walk the most repeated stack from its deepest frame up; the first frame present in
        // at least minShare of all samples is where the stall really sits.
        return stacks.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .flatMap(top -> top.getKey().stream()
                        .filter(frame -> framesInclusive.getOrDefault(frame, 0) >= minShare * samples)
                        .findFirst())
                .orElse("?");
    }

    public String render(String header, int topFrames, int topStacks) {
        StringBuilder out = new StringBuilder(header).append(System.lineSeparator());
        out.append(String.format(Locale.ROOT, "%d stack samples of the server thread%n%n", samples));
        if (samples == 0) {
            return out.toString();
        }
        out.append("Frames on the stack (share of samples, any depth):\n");
        framesInclusive.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topFrames)
                .forEach(entry -> out.append(String.format(Locale.ROOT, "  %5.1f%%  %s%n",
                        entry.getValue() * 100.0D / samples, entry.getKey())));
        out.append("\nMost repeated stacks (deepest frame first):\n");
        stacks.entrySet().stream()
                .sorted(Map.Entry.<List<String>, Integer>comparingByValue().reversed())
                .limit(topStacks)
                .forEach(entry -> {
                    out.append(String.format(Locale.ROOT, "-- %d samples (%.1f%%)%n", entry.getValue(),
                            entry.getValue() * 100.0D / samples));
                    entry.getKey().forEach(frame -> out.append("     at ").append(frame).append(System.lineSeparator()));
                });
        return out.toString();
    }

    static String frame(StackTraceElement element) {
        return element.getClassName() + "." + element.getMethodName();
    }
}
