package dimblend;

import dimblend.worldgen.RotatingBiomeSource;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.SlicedOverworldBiomeSource;
import dimblend.worldgen.SlicedOverworldChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class DimBlendRegistries {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, "dimblend");
    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, "dimblend");

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<RotatingChunkGenerator>> ROTATING_GENERATOR =
            CHUNK_GENERATORS.register("rotating", () -> RotatingChunkGenerator.CODEC);
    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<SlicedOverworldChunkGenerator>> SLICED_OVERWORLD_GENERATOR =
            CHUNK_GENERATORS.register("sliced_overworld", () -> SlicedOverworldChunkGenerator.CODEC);
    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<RotatingBiomeSource>> ROTATING_BIOME_SOURCE =
            BIOME_SOURCES.register("rotating", () -> RotatingBiomeSource.CODEC);
    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<SlicedOverworldBiomeSource>> SLICED_OVERWORLD_BIOME_SOURCE =
            BIOME_SOURCES.register("sliced_overworld", () -> SlicedOverworldBiomeSource.CODEC);

    public static final ResourceKey<Level> ROTATING_LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"));

    private DimBlendRegistries() {
    }

    public static void register(IEventBus bus) {
        CHUNK_GENERATORS.register(bus);
        BIOME_SOURCES.register(bus);
    }
}
