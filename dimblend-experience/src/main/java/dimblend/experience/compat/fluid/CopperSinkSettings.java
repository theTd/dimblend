package dimblend.experience.compat.fluid;

import dimblend.experience.Config;

/** Both the upstream COMMON setting and the synchronized dbx switch must allow infinity. */
public final class CopperSinkSettings {
    public static boolean infiniteAllowed(boolean upstream) {
        return upstream && Config.isLoaded() && Config.COPPER_SINK_INFINITE.get();
    }

    private CopperSinkSettings() { }
}
