package dimblend.purge;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config for deleting rotating-dimension region files far from every player. */
public final class RegionPurgeConfig {
    /** Separate file so the pregen keys in {@code dimblend-server.toml} stay untouched. */
    public static final String FILE_NAME = "dimblend-purge-server.toml";

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Delete rotating-dimension region files (region/entities/poi r.X.Z.mca) that are far from every player, while the server runs")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue KEEP_CHUNKS = BUILDER
            .comment("Keep every region file within this many chunks along X of an online player or a pregen logout anchor.",
                    "Raised at runtime to at least max(pregen xBehind, xAhead) + 8 and view distance + 8, so purge never fights pregen or player loading")
            .defineInRange("keepChunks", 96, 32, 4096);

    public static final ModConfigSpec.IntValue IDLE_SECONDS = BUILDER
            .comment("A region must stay outside the keep window with nothing loaded for this long before it is deleted")
            .defineInRange("idleSeconds", 120, 10, 86400);

    public static final ModConfigSpec.IntValue SCAN_INTERVAL_SECONDS = BUILDER
            .comment("Seconds between purge scans")
            .defineInRange("scanIntervalSeconds", 10, 1, 600);

    public static final ModConfigSpec.IntValue MAX_REGIONS_PER_SCAN = BUILDER
            .comment("Upper bound of regions deleted per scan (each region is up to three .mca files)")
            .defineInRange("maxRegionsPerScan", 2, 1, 64);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private RegionPurgeConfig() {
    }
}
