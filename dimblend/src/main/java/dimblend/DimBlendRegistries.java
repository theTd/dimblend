package dimblend;

import dimblend.block.WarpGateBlock;
import dimblend.block.WarpGateBlockEntity;
import dimblend.worldgen.RotatingBiomeSource;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.SlicedOverworldBiomeSource;
import dimblend.worldgen.SlicedOverworldChunkGenerator;
import dimblend.worldgen.YShiftedBiomeSource;
import dimblend.worldgen.YShiftedChunkGenerator;
import dimblend.worldgen.YShiftedDensity;
import dimblend.worldgen.YShiftedNoiseChunkGenerator;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DimBlendRegistries {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, "dimblend");
    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, "dimblend");
    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, "dimblend");
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks("dimblend");
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "dimblend");

    /** No BlockItem: players must never obtain the gate (worldgen-only block). */
    public static final DeferredBlock<WarpGateBlock> WARP_GATE =
            BLOCKS.register("warp_gate", () -> new WarpGateBlock(WarpGateBlock.createProperties()));

    /**
     * Own type (vanilla END_GATEWAY only accepts Blocks.END_GATEWAY for ticking) so the
     * renderer and ticker bind to the warp gate block; see {@link WarpGateBlockEntity}.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WarpGateBlockEntity>> WARP_GATE_BE =
            BLOCK_ENTITY_TYPES.register("warp_gate", () ->
                    BlockEntityType.Builder.of(WarpGateBlockEntity::new, WARP_GATE.get()).build(null));

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<RotatingChunkGenerator>> ROTATING_GENERATOR =
            CHUNK_GENERATORS.register("rotating", () -> RotatingChunkGenerator.CODEC);

    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<RotatingBiomeSource>> ROTATING_BIOME_SOURCE =
            BIOME_SOURCES.register("rotating", () -> RotatingBiomeSource.CODEC);
    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<SlicedOverworldChunkGenerator>> SLICED_OVERWORLD_GENERATOR =
            CHUNK_GENERATORS.register("sliced_overworld", () -> SlicedOverworldChunkGenerator.CODEC);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<YShiftedNoiseChunkGenerator>> Y_SHIFTED_NOISE_GENERATOR =
            CHUNK_GENERATORS.register("y_shifted_noise", () -> YShiftedNoiseChunkGenerator.CODEC);
    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<YShiftedChunkGenerator>> Y_SHIFTED_GENERATOR =
            CHUNK_GENERATORS.register("y_shifted", () -> YShiftedChunkGenerator.CODEC);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<YShiftedDensity>> Y_SHIFTED_DENSITY =
            DENSITY_FUNCTION_TYPES.register("y_shifted", () -> YShiftedDensity.DIRECT_CODEC);

    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<SlicedOverworldBiomeSource>> SLICED_OVERWORLD_BIOME_SOURCE =
            BIOME_SOURCES.register("sliced_overworld", () -> SlicedOverworldBiomeSource.CODEC);
    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<YShiftedBiomeSource>> Y_SHIFTED_BIOME_SOURCE =
            BIOME_SOURCES.register("y_shifted", () -> YShiftedBiomeSource.CODEC);


    public static final ResourceKey<Level> ROTATING_LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"));

    private DimBlendRegistries() {
    }

    public static void register(IEventBus bus) {
        CHUNK_GENERATORS.register(bus);
        BIOME_SOURCES.register(bus);
        DENSITY_FUNCTION_TYPES.register(bus);
        BLOCKS.register(bus);
        BLOCK_ENTITY_TYPES.register(bus);
    }
}
