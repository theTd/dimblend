package dimblend.carwash;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue SOILING_PROBABILITY_MULTIPLIER = BUILDER
            .comment("Travel dirtiness probability = min(1, speed / 12 * multiplier); default 0.5. Range 0.00-1.00, step 0.01. 0 disables dirtiness draws; washing is unaffected.")
            .defineInRange("soilingProbabilityMultiplier", 0.5D, 0.0D, 1.0D);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
