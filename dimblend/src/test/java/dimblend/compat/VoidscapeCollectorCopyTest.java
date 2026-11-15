package dimblend.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class VoidscapeCollectorCopyTest {
    @Test
    void costsMatchVoidscapeMachineRules() {
        assertEquals(1, VoidscapeCollectorCopy.costFor(ResourceLocation.withDefaultNamespace("cobblestone")));
        for (String path : new String[] {"veridium", "asurine", "ochrum", "crimsite"}) {
            assertEquals(10, VoidscapeCollectorCopy.costFor(ResourceLocation.fromNamespaceAndPath("create", path)));
        }
        assertEquals(0, VoidscapeCollectorCopy.costFor(ResourceLocation.withDefaultNamespace("stone")));
        assertEquals(0, VoidscapeCollectorCopy.costFor(ResourceLocation.fromNamespaceAndPath("other", "veridium")));
    }
}
