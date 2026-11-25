package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * G4 离境禁的同维度放行口径（2026-11-25 末影珍珠修复）。
 */
class PortalBanRulesTest {

    private static ResourceKey<Level> dim(String id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
    }

    @Test
    void enderPearlStyleSameDimensionTravelIsAllowed() {
        assertTrue(PortalBanRules.isSameDimension(dim("dimblend:rotating"), dim("dimblend:rotating")));
        assertTrue(PortalBanRules.isSameDimension(dim("minecraft:overworld"), dim("minecraft:overworld")));
    }

    @Test
    void crossDimensionTravelIsStillBanned() {
        assertFalse(PortalBanRules.isSameDimension(dim("dimblend:rotating"), dim("minecraft:the_nether")));
        assertFalse(PortalBanRules.isSameDimension(dim("dimblend:rotating"), dim("minecraft:overworld")));
    }

    @Test
    void nullDestinationNeverSlipsThrough() {
        assertFalse(PortalBanRules.isSameDimension(null, null));
    }
}
