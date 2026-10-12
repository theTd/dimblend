package dimblend.experience.tuning.perf;

import java.util.List;

/**
 * One step of the performance checklist, in wizard order (largest stall source first).
 *
 * <p>Each step owns its translation keys under {@link #PREFIX}: {@code <id>} (title),
 * {@code <id>.why}, {@code <id>.current} and {@code <id>.advice.<advice>} for every
 * advice it can report; fixable steps also have {@code <id>.fix} (the button label).
 */
public enum PerfCheck {
    SPARK_BACKGROUND_PROFILER("spark_background_profiler", true, "off", "on", "restart", "absent"),
    GC_COLLECTOR("gc_collector", false, "zgc_generational", "zgc_single", "g1", "other"),
    HEAP_HEADROOM("heap_headroom", false, "ok", "low", "critical"),
    SYSTEM_MEMORY("system_memory", false, "ok", "low", "critical"),
    ASYNC_SAVE("async_save", false, "active", "inactive", "missing"),
    FINITE_WATER("finite_water", false, "absent", "present"),
    OVERWORLD_UNLOAD("overworld_unload", true, "ok", "spawn_chunks", "forced"),
    SIMULATION_DISTANCE("simulation_distance", true, "ok", "high", "high_dedicated"),
    RENDER_DISTANCE("render_distance", true, "ok", "high"),
    SABLE_PHYSICS("sable_physics", false, "defaults", "custom", "missing"),
    SABLE_INTERPOLATION("sable_interpolation", true, "ok", "short", "missing");

    public static final String PREFIX = "screen.dimblend_experience.perf.";
    private final String id;
    private final boolean fixable;
    private final List<String> advices;

    PerfCheck(String id, boolean fixable, String... advices) {
        this.id = id;
        this.fixable = fixable;
        this.advices = List.of(advices);
    }

    public String id() { return id; }
    public String key() { return PREFIX + id; }
    /** Whether some advice of this step offers a one-click fix. */
    public boolean fixable() { return fixable; }
    public List<String> advices() { return advices; }

    public static PerfCheck byId(String id) {
        for (PerfCheck check : values()) {
            if (check.id.equals(id)) {
                return check;
            }
        }
        return null;
    }
}
