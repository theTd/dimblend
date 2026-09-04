package dimblend.worldgen;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class PregenConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Opportunistic FULL pre-generation of the rotating corridor strip")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue X_BEHIND = BUILDER
            .comment("Chunks behind the player along X. Window is [playerX - xBehind, playerX + xAhead)")
            .defineInRange("xBehind", 64, 1, 512);

    public static final ModConfigSpec.IntValue X_AHEAD = BUILDER
            .comment("Chunks ahead of the player along X (exclusive bound)")
            .defineInRange("xAhead", 64, 1, 512);

    public static final ModConfigSpec.IntValue Z_MIN = BUILDER
            .comment("Fixed minimum chunk Z of the strip")
            .defineInRange("zMin", -16, -4096, 4096);

    public static final ModConfigSpec.IntValue Z_MAX = BUILDER
            .comment("Fixed maximum chunk Z of the strip, inclusive")
            .defineInRange("zMax", 15, -4096, 4096);

    public static final ModConfigSpec.IntValue MAX_IN_FLIGHT = BUILDER
            .comment("Upper bound of chunks simultaneously driven to FULL")
            .defineInRange("maxInFlight", 16, 1, 128);

    public static final ModConfigSpec.IntValue BRAKE_TICK_MS = BUILDER
            .comment("Average tick time above this halves the in-flight budget")
            .defineInRange("brakeTickMs", 40, 20, 100);

    public static final ModConfigSpec.IntValue OK_TICK_MS = BUILDER
            .comment("Average tick time below this with an idle worldgen pool raises the budget")
            .defineInRange("okTickMs", 30, 10, 100);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private PregenConfig() {
    }
}
