package dimblend.api;

import dimblend.diagnostics.SkyTimeTraceSettings;

/** Local diagnostic settings exposed to optional configuration panels. No client engine types cross this API. */
public final class SkyTimeTraceTuning {
    public record State(boolean enabled, long revision) { }

    public static boolean isLoaded() { return SkyTimeTraceSettings.SPEC.isLoaded(); }
    public static State state() { return SkyTimeTraceSettings.state(); }
    public static boolean enabled() { return state().enabled(); }
    public static void setEnabled(boolean enabled) { SkyTimeTraceSettings.setEnabled(enabled); }

    private SkyTimeTraceTuning() { }
}
