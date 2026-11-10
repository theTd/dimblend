package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class AetherPlacementBanRulesTest {
    private static final int BAND_SIZE = 2048;
    private static final IntFunction<String> LANES = region -> switch (region) {
        case -2, 3 -> "aether";
        case -1, 2 -> "surface";
        default -> "voidscape";
    };

    @Test
    void onlyTheRotatingAetherLaneOverridesRecipes() {
        assertTrue(AetherPlacementBanRules.shouldLiftBan(true, 6144, BAND_SIZE, LANES));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(false, 6144, BAND_SIZE, LANES),
                "native Aether and every other dimension keep their original bans");
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 4096, BAND_SIZE, LANES));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 8192, BAND_SIZE, LANES));
    }

    @Test
    void checkedBlockOwnsTheBoundaryIncludingNegativeCoordinates() {
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 6143, BAND_SIZE, LANES));
        assertTrue(AetherPlacementBanRules.shouldLiftBan(true, 6144, BAND_SIZE, LANES));
        assertTrue(AetherPlacementBanRules.shouldLiftBan(true, 8191, BAND_SIZE, LANES));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 8192, BAND_SIZE, LANES));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, -4097, BAND_SIZE, LANES));
        assertTrue(AetherPlacementBanRules.shouldLiftBan(true, -4096, BAND_SIZE, LANES));
        assertTrue(AetherPlacementBanRules.shouldLiftBan(true, -2049, BAND_SIZE, LANES));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, -2048, BAND_SIZE, LANES));
    }

    @Test
    void missingLayoutNeverGuessesOrQueriesAnInvalidRegion() {
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 6144, BAND_SIZE, null));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 6144, 0, LANES));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 6144, -1, LANES));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(true, 6144, BAND_SIZE, region -> null));
        assertFalse(AetherPlacementBanRules.shouldLiftBan(false, 6144, BAND_SIZE,
                region -> { throw new AssertionError("other dimensions must not read this layout"); }));
    }

    @Test
    void predictionIsLimitedToTheCheckedAetherBiomeInRotating() {
        assertTrue(AetherPlacementBanRules.predictLiftBan(true, "aether"));
        assertFalse(AetherPlacementBanRules.predictLiftBan(false, "aether"));
        assertFalse(AetherPlacementBanRules.predictLiftBan(true, "minecraft"));
        assertFalse(AetherPlacementBanRules.predictLiftBan(true, null));
    }
}