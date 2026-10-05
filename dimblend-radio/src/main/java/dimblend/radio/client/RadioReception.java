package dimblend.radio.client;

import dimblend.radio.SubLevelProjection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;

/**
 * How well a radio receives where it stands, and what that sounds like. Read from the biome at
 * the radio (projected out of a Sable structure), so a band of the rotating dimension that
 * borrows the End or the Nether sounds like that place, as do the real dimensions.
 *
 * @param music the track's level
 * @param hiss band-limited static's level (amplitude of unit-variance noise before filtering)
 * @param crackleRate crackles per second
 * @param crackle a crackle's peak level
 * @param fading how deep the slow signal fades go: the track dips by up to this share while the
 *               static swells by as much
 * @param voices faint enderman voices drift in now and then
 */
public enum RadioReception {
    /** Overworld and underground. */
    CLEAR(1, 0, 0, 0, 0, false),
    /** The Nether and modded areas. */
    FAINT_STATIC(1, 0.025f, 1.5f, 0.05f, 0.3f, false),
    /** The End. */
    ENDER_VOICES(1, 0, 0, 0, 0, true),
    /** Voidscape: nothing comes through but static. */
    STATIC_ONLY(0, 0.16f, 12, 0.2f, 0.4f, false);

    private static final String VOIDSCAPE = "voidscape";
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");
    private static final ResourceLocation END = ResourceLocation.withDefaultNamespace("the_end");

    final float music, hiss, crackleRate, crackle, fading;
    final boolean voices;

    RadioReception(float music, float hiss, float crackleRate, float crackle, float fading, boolean voices) {
        this.music = music;
        this.hiss = hiss;
        this.crackleRate = crackleRate;
        this.crackle = crackle;
        this.fading = fading;
        this.voices = voices;
    }

    /** Client thread: the reception of the radio at {@code pos} (plot coordinates on a structure). */
    public static RadioReception at(Level level, BlockPos pos) {
        // Use the received biome cell directly: no fuzzy boundary drift or cached loading fallback.
        Holder<Biome> biome = level.getBiomeManager().getNoiseBiomeAtPosition(
                BlockPos.containing(SubLevelProjection.worldCenter(level, pos)));
        String biomeNamespace = biome.unwrapKey().map(key -> key.location().getNamespace()).orElse("");
        return classify(level.dimension().location(), biomeNamespace,
                biome.is(BiomeTags.IS_END) || biome.is(Tags.Biomes.IS_END),
                biome.is(BiomeTags.IS_NETHER) || biome.is(Tags.Biomes.IS_NETHER),
                biome.is(BiomeTags.IS_OVERWORLD) || biome.is(Tags.Biomes.IS_OVERWORLD));
    }

    /**
     * Voidscape (its dimension or its biomes) first, then the End, the Nether and the overworld by
     * biome tag or dimension; anything else is a modded area.
     */
    static RadioReception classify(ResourceLocation dimension, String biomeNamespace,
            boolean endBiome, boolean netherBiome, boolean overworldBiome) {
        if (VOIDSCAPE.equals(dimension.getNamespace()) || VOIDSCAPE.equals(biomeNamespace)) return STATIC_ONLY;
        if (endBiome || END.equals(dimension)) return ENDER_VOICES;
        if (netherBiome || NETHER.equals(dimension)) return FAINT_STATIC;
        if (overworldBiome || OVERWORLD.equals(dimension)) return CLEAR;
        return FAINT_STATIC;
    }
}
