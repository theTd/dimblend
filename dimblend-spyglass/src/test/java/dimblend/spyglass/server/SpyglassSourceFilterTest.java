package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SpyglassSourceFilterTest {
    private static final ResourceLocation TERRALITH_X =
            ResourceLocation.fromNamespaceAndPath("terralith", "x");
    private static final ResourceLocation TERRALITH_Y =
            ResourceLocation.fromNamespaceAndPath("terralith", "y");
    private static final ResourceLocation DESERT_PYRAMID =
            ResourceLocation.fromNamespaceAndPath("minecraft", "desert_pyramid");

    @Test
    void keepsASourceWhoseNamespaceIsNotDisabled() {
        assertTrue(SpyglassSourceFilter.keep(TERRALITH_X, namespace -> false));
    }

    @Test
    void dropsASourceWhoseNamespaceIsDisabled() {
        assertFalse(SpyglassSourceFilter.keep(TERRALITH_Y, Set.of("terralith")::contains));
    }

    @Test
    void keepsMinecraftEvenWhenItsNamespaceIsInTheDisabledSet() {
        assertTrue(SpyglassSourceFilter.keep(DESERT_PYRAMID, Set.of("minecraft")::contains));
    }

    @Test
    void acceptsOnlyValidNamespaces() {
        assertTrue(SpyglassSourceFilter.isValidNamespace("terralith"));
        assertTrue(SpyglassSourceFilter.isValidNamespace("dungeons_and_taverns"));
        assertTrue(SpyglassSourceFilter.isValidNamespace("a.b-c_d"));
        assertFalse(SpyglassSourceFilter.isValidNamespace(null));
        assertFalse(SpyglassSourceFilter.isValidNamespace(""));
        assertFalse(SpyglassSourceFilter.isValidNamespace("terralith:house"));
        assertFalse(SpyglassSourceFilter.isValidNamespace("Bad Namespace"));
        assertFalse(SpyglassSourceFilter.isValidNamespace("a/b"));
    }
}
