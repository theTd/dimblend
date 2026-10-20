package dimblend.experience.client;

import dimblend.api.SkyTimeTraceTuning;

/** Loaded only after the core mod presence check; local settings never enter the server tuning protocol. */
final class SkyTimeTraceClientBridge {
    static boolean isLoaded() { return SkyTimeTraceTuning.isLoaded(); }
    static boolean enabled() { return SkyTimeTraceTuning.enabled(); }
    static void setEnabled(boolean enabled) { SkyTimeTraceTuning.setEnabled(enabled); }

    private SkyTimeTraceClientBridge() { }
}
