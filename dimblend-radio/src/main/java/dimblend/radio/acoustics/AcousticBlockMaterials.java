package dimblend.radio.acoustics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * Hardcoded block-to-material mapping ({@link AcousticMaterials} indices) shared by every geometry
 * path: the voxel tracer, the voxel mesher and the Sodium mesh tee. Tags come first so datapacks
 * and mods can opt blocks in; then the block's sound type, which modded blocks usually set
 * sensibly. A modded block that both leave as stone gets a material guessed from its name
 * ({@link AcousticBlockNames}); everything else is stone. Vanilla blocks never go by name.
 * <p>
 * Natural terrain deliberately collapses to a few classes (all rock, ore and deepslate is stone;
 * dirt, grass, sand, gravel and mud are soil): the GPU mesh merges only coplanar faces of one
 * material, so splitting common terrain would multiply its triangles without an audible gain.
 */
public final class AcousticBlockMaterials {
    private static final Set<SoundType> POROUS = Set.of(SoundType.WOOL, SoundType.MOSS, SoundType.MOSS_CARPET,
            SoundType.SPONGE, SoundType.WET_SPONGE);
    private static final Set<SoundType> SOIL = Set.of(SoundType.GRASS, SoundType.GRAVEL, SoundType.SAND,
            SoundType.SUSPICIOUS_SAND, SoundType.SUSPICIOUS_GRAVEL, SoundType.ROOTED_DIRT, SoundType.MUD,
            SoundType.MUDDY_MANGROVE_ROOTS, SoundType.SOUL_SAND, SoundType.SOUL_SOIL);
    private static final Set<SoundType> WOOD = Set.of(SoundType.WOOD, SoundType.CHERRY_WOOD, SoundType.NETHER_WOOD,
            SoundType.BAMBOO_WOOD, SoundType.STEM);
    private static final Set<SoundType> METAL = Set.of(SoundType.METAL, SoundType.COPPER, SoundType.COPPER_BULB,
            SoundType.COPPER_GRATE, SoundType.NETHERITE_BLOCK, SoundType.ANVIL, SoundType.CHAIN, SoundType.LANTERN,
            SoundType.HEAVY_CORE, SoundType.VAULT, SoundType.TRIAL_SPAWNER);
    /** Name guesses per block; registry names never change, so this is never cleared. */
    private static final Map<Block, Integer> BY_NAME = new ConcurrentHashMap<>();

    /** Material index of a solid block; callers decide whether the block is solid at all. */
    public static int of(BlockState state) {
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.BEDS)
                || state.is(Blocks.HAY_BLOCK)) {
            return AcousticMaterials.WOOL;
        }
        if (state.is(BlockTags.LEAVES)) return AcousticMaterials.FOLIAGE;
        if (state.is(BlockTags.ICE)) return AcousticMaterials.ICE;
        if (state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)) return AcousticMaterials.GLASS;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) return AcousticMaterials.WOOD;
        SoundType sound = state.getSoundType();
        if (POROUS.contains(sound)) return AcousticMaterials.WOOL;
        if (sound == SoundType.SNOW) return AcousticMaterials.SNOW;
        if (SOIL.contains(sound)) return AcousticMaterials.SOIL;
        if (WOOD.contains(sound)) return AcousticMaterials.WOOD;
        if (sound == SoundType.GLASS) return AcousticMaterials.GLASS;
        if (METAL.contains(sound)) return AcousticMaterials.METAL;
        return BY_NAME.computeIfAbsent(state.getBlock(), AcousticBlockMaterials::byName);
    }

    private static int byName(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) return AcousticMaterials.STONE;
        int material = AcousticBlockNames.infer(id.getPath());
        return material == AcousticBlockNames.UNKNOWN ? AcousticMaterials.STONE : material;
    }

    private AcousticBlockMaterials() { }
}
