package dimblend.worldgen;

import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Avoids repeatedly mapping shared density branches during rotating structure placement.
 * The scope stays on the worker performing placement and never shares NoiseChunk's
 * mutable interpolation state between tasks or columns.
 */
public final class StructureNoiseSetup {
    private static final boolean ENABLED = !Boolean.getBoolean("dimblend.disableStructureNoiseOptimization");
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();
    private static final ThreadLocal<Mapping> MAPPING = new ThreadLocal<>();

    private record Mapping(DensityFunction.Visitor visitor,
                           IdentityHashMap<DensityFunction, DensityFunction> references) { }

    private StructureNoiseSetup() {
    }

    public static void run(Runnable generation) {
        if (!ENABLED) {
            generation.run();
            return;
        }
        Integer previous = DEPTH.get();
        DEPTH.set(previous == null ? 1 : previous + 1);
        try {
            generation.run();
        } finally {
            if (previous == null) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }

    /** Ordinary terrain generation retains vanilla traversal. */
    public static boolean active() {
        return DEPTH.get() != null;
    }

    /** A result may contain mutable interpolators: its cache belongs to exactly this router build. */
    public static <T> T mapNodes(DensityFunction.Visitor visitor, Supplier<T> mapping) {
        if (!active()) {
            return mapping.get();
        }
        Mapping previous = MAPPING.get();
        MAPPING.set(new Mapping(visitor, new IdentityHashMap<>()));
        try {
            return mapping.get();
        } finally {
            if (previous == null) {
                MAPPING.remove();
            } else {
                MAPPING.set(previous);
            }
        }
    }

    public static DensityFunction mapReference(DensityFunction source, DensityFunction.Visitor visitor,
                                               Supplier<DensityFunction> mapping) {
        Mapping current = MAPPING.get();
        if (current == null || current.visitor != visitor) {
            return mapping.get();
        }
        DensityFunction cached = current.references.get(source);
        if (cached != null) {
            return cached;
        }
        DensityFunction mapped = mapping.get();
        current.references.put(source, mapped);
        return mapped;
    }

    /** Let ordinary mapAll calls bypass the memoization supplier entirely. */
    public static boolean mappingActive(DensityFunction.Visitor visitor) {
        Mapping current = MAPPING.get();
        return current != null && current.visitor == visitor;
    }
}
