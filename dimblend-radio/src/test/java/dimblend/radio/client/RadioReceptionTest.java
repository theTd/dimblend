package dimblend.radio.client;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioReceptionTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");
    private static final ResourceLocation END = ResourceLocation.withDefaultNamespace("the_end");
    private static final ResourceLocation ROTATING = ResourceLocation.fromNamespaceAndPath("dimblend", "rotating");

    @Test
    void vanillaDimensionsGoByTheirOwnPlace() {
        assertEquals(RadioReception.CLEAR, RadioReception.classify(OVERWORLD, "minecraft", false, false, true));
        assertEquals(RadioReception.FAINT_STATIC, RadioReception.classify(NETHER, "minecraft", false, true, false));
        assertEquals(RadioReception.ENDER_VOICES, RadioReception.classify(END, "minecraft", true, false, false));
    }

    @Test
    void untaggedModdedBiomesInTheOverworldStayClear() {
        assertEquals(RadioReception.CLEAR, RadioReception.classify(OVERWORLD, "terralith", false, false, false));
    }

    /** Bands of the rotating dimension sound like the place they borrow. */
    @Test
    void rotatingBandsGoByTheirBiome() {
        assertEquals(RadioReception.CLEAR, RadioReception.classify(ROTATING, "minecraft", false, false, true));
        assertEquals(RadioReception.FAINT_STATIC, RadioReception.classify(ROTATING, "minecraft", false, true, false));
        assertEquals(RadioReception.ENDER_VOICES, RadioReception.classify(ROTATING, "minecraft", true, false, false));
        assertEquals(RadioReception.STATIC_ONLY, RadioReception.classify(ROTATING, "voidscape", false, false, false));
        for (String mod : new String[] {"deeperdarker", "aether", "eternal_starlight", "twilightforest"}) {
            assertEquals(RadioReception.FAINT_STATIC, RadioReception.classify(ROTATING, mod, false, false, false), mod);
        }
    }

    @Test
    void voidscapeAndModdedDimensions() {
        ResourceLocation voidscape = ResourceLocation.fromNamespaceAndPath("voidscape", "void");
        assertEquals(RadioReception.STATIC_ONLY, RadioReception.classify(voidscape, "voidscape", false, false, false));
        assertEquals(RadioReception.STATIC_ONLY, RadioReception.classify(voidscape, "minecraft", false, false, true));
        ResourceLocation aether = ResourceLocation.fromNamespaceAndPath("aether", "the_aether");
        assertEquals(RadioReception.FAINT_STATIC, RadioReception.classify(aether, "aether", false, false, false));
    }
}
