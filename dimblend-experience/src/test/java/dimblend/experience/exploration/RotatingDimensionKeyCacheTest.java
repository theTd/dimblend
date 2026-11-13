package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class RotatingDimensionKeyCacheTest {

    @Test
    void cachesResolvedKeyAndRefreshesOnlyWhenConfigStringChanges() {
        ResourceKey<Level> first = RotatingDimension.resolveKey("dimblend:rotating");
        int baselineParses = RotatingDimension.parseCountForTest();

        for (int i = 0; i < 100; i++) {
            assertSame(first, RotatingDimension.resolveKey("dimblend:rotating"));
        }
        assertEquals(baselineParses, RotatingDimension.parseCountForTest(),
                "repeated lookups with identical raw id must hit cache without re-parsing");

        // GameTests dynamically switch ROTATING_DIMENSION_ID to minecraft:overworld
        ResourceKey<Level> overworld = RotatingDimension.resolveKey("minecraft:overworld");
        assertEquals("minecraft:overworld", overworld.location().toString());
        assertEquals(baselineParses + 1, RotatingDimension.parseCountForTest(),
                "changing config string must re-resolve once");
        for (int i = 0; i < 50; i++) {
            assertSame(overworld, RotatingDimension.resolveKey("minecraft:overworld"));
        }
        assertEquals(baselineParses + 1, RotatingDimension.parseCountForTest());

        // Invalid bare id without namespace falls back to dimblend:rotating and also caches
        ResourceKey<Level> fallback = RotatingDimension.resolveKey("rotating_without_colon");
        assertEquals("dimblend:rotating", fallback.location().toString());
        int afterInvalid = RotatingDimension.parseCountForTest();
        assertSame(fallback, RotatingDimension.resolveKey("rotating_without_colon"));
        assertEquals(afterInvalid, RotatingDimension.parseCountForTest());

        // Restore default cache state
        assertEquals("dimblend:rotating", RotatingDimension.resolveKey("dimblend:rotating").location().toString());
    }
}
