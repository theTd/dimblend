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
            .comment("Starting in-flight budget when no players are online; pressure always stops admission")
            .defineInRange("minInFlight", 4, 1, 128);

    public static final ModConfigSpec.IntValue MAX_IN_FLIGHT = BUILDER
            .comment("Upper bound of chunks simultaneously driven to FULL")
            .defineInRange("maxInFlight", 16, 1, 128);

    public static final ModConfigSpec.IntValue ONLINE_MAX_IN_FLIGHT = BUILDER
            .comment("Maximum in-flight targets while anyone is playing; cancelling targets remain counted")
            .defineInRange("onlineMaxInFlight", 1, 1, 4);

    public static final ModConfigSpec.DoubleValue MAX_SYSTEM_CPU_LOAD = BUILDER
            .comment("Admission requires system CPU load below this fraction; unavailable readings stop pregen")
            .defineInRange("maxSystemCpuLoad", 0.5, 0.1, 0.75);

    public static final ModConfigSpec.IntValue RECOVERY_SECONDS = BUILDER
            .comment("All capacity signals must stay healthy this long before pregen resumes")
            .defineInRange("recoverySeconds", 3, 3, 60);

    public static final ModConfigSpec.IntValue BRAKE_TICK_MS = BUILDER
            .comment("Average or latest tick above this stops pregen immediately")
            .defineInRange("brakeTickMs", 40, 20, 100);

    public static final ModConfigSpec.IntValue OK_TICK_MS = BUILDER
            .comment("Average tick time below this raises the in-flight budget after a healthy streak")
            .defineInRange("okTickMs", 30, 10, 100);

    public static final ModConfigSpec.IntValue RAISE_STREAK_TICKS = BUILDER
            .comment("Consecutive healthy ticks required before raising the in-flight budget")
            .defineInRange("raiseStreakTicks", 10, 1, 200);

    public static final ModConfigSpec.IntValue PLAYER_PROXIMITY_RADIUS = BUILDER
            .comment("Minimum exclusion radius; runtime also excludes player view distance plus the generation dependency margin")
            .defineInRange("playerProximityRadius", 4, 0, 16);

    public static final ModConfigSpec.BooleanValue MESH_GATE = BUILDER
            .comment("Retained for config compatibility; integrated pregen always requires healthy frames and an idle mesh pipeline")
            .define("meshGate", true);

    public static final ModConfigSpec.BooleanValue CANCEL_ON_POOL_BACKLOG = BUILDER
            .comment("Retained for config compatibility; capacity pressure always cancels pregen tickets immediately")
            .define("cancelOnPoolBacklog", true);

    public static final ModConfigSpec.IntValue BACKLOG_CANCEL_STREAK = BUILDER
            .comment("Legacy setting; backlog cancellation is now immediate")
            .defineInRange("backlogCancelStreak", 2, 1, 20);

    public static final ModConfigSpec.BooleanValue PREGEN_ONLY_BEHIND = BUILDER
            .comment("Only pregenerate chunks behind the player (negative X), never ahead")
            .define("pregenOnlyBehind", false);

    public static final ModConfigSpec.BooleanValue YIELD_TO_FOREIGN_GEN = BUILDER
            .comment("Retained for config compatibility; player and non-pregen loading demand always takes priority")
            .define("yieldToForeignGen", true);

    public static final ModConfigSpec.IntValue FOREIGN_YIELD_CANCEL_STREAK = BUILDER
            .comment("Legacy setting; foreign demand cancellation is now immediate when detected")
            .defineInRange("foreignYieldCancelStreak", 2, 1, 20);

    public static final ModConfigSpec.IntValue FOREIGN_YIELD_RESUME_TICKS = BUILDER
            .comment("Legacy setting; recoverySeconds now controls the recovery period")
            .defineInRange("foreignYieldResumeTicks", 20, 1, 200);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private PregenConfig() {
    }
}
