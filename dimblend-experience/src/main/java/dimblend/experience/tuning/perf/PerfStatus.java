package dimblend.experience.tuning.perf;

/** Severity of one checklist result, ordered from fine to worst for sorting and colouring. */
public enum PerfStatus {
    OK(0xFF55FF55),
    INFO(0xFF7FB2FF),
    UNAVAILABLE(0xFF9A9A9A),
    WARN(0xFFFFD24D),
    PROBLEM(0xFFFF5555);

    private final int color;

    PerfStatus(int color) {
        this.color = color;
    }

    public int color() { return color; }
    public String key() { return PerfCheck.PREFIX + "status." + name().toLowerCase(java.util.Locale.ROOT); }
    /** Statuses that still ask the user to act. */
    public boolean needsAttention() { return this == WARN || this == PROBLEM; }
}
