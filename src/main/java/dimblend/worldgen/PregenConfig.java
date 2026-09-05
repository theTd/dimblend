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

    public static final ModConfigSpec.IntValue MIN_IN_FLIGHT = BUILDER
            .comment("Starting in-flight budget. Hard brake may still drop to 1")
            .defineInRange("minInFlight", 4, 1, 128);

    public static final ModConfigSpec.IntValue MAX_IN_FLIGHT = BUILDER
            .comment("Upper bound of chunks simultaneously driven to FULL")
            .defineInRange("maxInFlight", 16, 1, 128);

    public static final ModConfigSpec.IntValue BRAKE_TICK_MS = BUILDER
            .comment("Average tick time above this halves the in-flight budget")
            .defineInRange("brakeTickMs", 40, 20, 100);

    public static final ModConfigSpec.IntValue OK_TICK_MS = BUILDER
            .comment("Average tick time below this raises the in-flight budget after a healthy streak")
            .defineInRange("okTickMs", 30, 10, 100);

    public static final ModConfigSpec.IntValue RAISE_STREAK_TICKS = BUILDER
            .comment("Consecutive healthy ticks required before raising the in-flight budget")
            .defineInRange("raiseStreakTicks", 10, 1, 200);

    public static final ModConfigSpec.IntValue PLAYER_PROXIMITY_RADIUS = BUILDER
            .comment("Do not issue pregen tickets within this chunk radius of any player")
            .defineInRange("playerProximityRadius", 4, 0, 16);

    public static final ModConfigSpec.IntValue MOVING_CHUNK_THRESHOLD = BUILDER
            .comment("Treat a player as 'moving fast' if they cross this many chunks in one rescan interval")
            .defineInRange("movingChunkThreshold", 2, 0, 16);

    public static final ModConfigSpec.BooleanValue CANCEL_ON_POOL_BACKLOG = BUILDER
            .comment("Cancel in-flight pregen tickets when the worldgen pool stays backlogged")
            .define("cancelOnPoolBacklog", true);

    public static final ModConfigSpec.IntValue BACKLOG_CANCEL_STREAK = BUILDER
            .comment("Consecutive ticks the pool must be backlogged before cancelling in-flight tickets")
            .defineInRange("backlogCancelStreak", 2, 1, 20);

    public static final ModConfigSpec.BooleanValue PREGEN_ONLY_BEHIND = BUILDER
            .comment("Only pregenerate chunks behind the player (negative X), never ahead")
            .define("pregenOnlyBehind", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private PregenConfig() {
    }
}
