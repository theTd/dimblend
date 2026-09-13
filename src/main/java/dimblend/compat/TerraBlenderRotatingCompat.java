package dimblend.compat;

import dimblend.worldgen.OceanFilteredBiomeSource;
import dimblend.worldgen.OverworldSlice;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.SlicedOverworldChunkGenerator;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

public final class TerraBlenderRotatingCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private TerraBlenderRotatingCompat() {
    }

    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!ModList.get().isLoaded("terrablender")) {
            return;
        }
        initializeRotatingDelegates(event.getServer());
    }

    private static void initializeRotatingDelegates(MinecraftServer server) {
        Method initializeBiomes;
        try {
            initializeBiomes = Class.forName("terrablender.util.LevelUtils").getMethod(
                    "initializeBiomes",
                    RegistryAccess.class,
                    Holder.class,
                    ResourceKey.class,
                    ChunkGenerator.class,
                    long.class
            );
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            LOGGER.warn("TerraBlender is loaded but LevelUtils.initializeBiomes is missing", exception);
            return;
        }

        RegistryAccess access = server.registryAccess();
        Registry<LevelStem> stems = access.registryOrThrow(Registries.LEVEL_STEM);
        LevelStem rotating = stems.get(ResourceKey.create(
                Registries.LEVEL_STEM,
                ResourceLocation.fromNamespaceAndPath("dimblend", "rotating")
        ));
        if (rotating == null || !(rotating.generator() instanceof RotatingChunkGenerator rotatingGenerator)) {
            return;
        }

        LevelStem overworld = stems.get(LevelStem.OVERWORLD);
        LevelStem nether = stems.get(LevelStem.NETHER);
        LevelStem end = stems.get(LevelStem.END);
        long seed = server.getWorldData().worldGenOptions().seed();

        for (ChunkGenerator delegate : rotatingGenerator.delegates()) {
            Holder<DimensionType> dimensionType = dimensionTypeForDelegate(delegate, overworld, nether, end);
            ResourceKey<LevelStem> logKey = bandStemKey(delegate);
            if (dimensionType == null || logKey == null) {
                continue;
            }
            ChunkGenerator target = delegate instanceof SlicedOverworldChunkGenerator sliced
                    ? sliced.inner()
                    : delegate;
            ChunkGenerator terraBlenderTarget = unwrapOceanFilter(target);
            if (terraBlenderTarget != target) {
                LOGGER.info("Unwrapped dimblend ocean filter for TerraBlender init of {}", logKey.location());
            }
            try {
                initializeBiomes.invoke(null, access, dimensionType, logKey, terraBlenderTarget, seed);
                LOGGER.info("Initialized TerraBlender biomes for rotating band {}", logKey.location());
            } catch (ReflectiveOperationException exception) {
                Throwable cause = exception instanceof InvocationTargetException && exception.getCause() != null
                        ? exception.getCause()
                        : exception;
                LOGGER.warn("TerraBlender init failed for {}", logKey.location(), cause);
            }
        }
    }

    /**
     * TerraBlender's {@code initializeBiomes} gates on the delegate's biome source being a
     * vanilla {@code MultiNoiseBiomeSource} (silently skipping anything else) and mutates
     * that instance in place. Underground slices wrap their multi-noise source in
     * {@link OceanFilteredBiomeSource}, so hand TerraBlender an unfiltered view of the same
     * inner source: the injection lands on the identical instance the wrapper delegates to,
     * and region biomes keep flowing through the filter (tagged oceans stay excluded).
     */
    private static ChunkGenerator unwrapOceanFilter(ChunkGenerator inner) {
        if (inner instanceof NoiseBasedChunkGenerator noise
                && noise.getBiomeSource() instanceof OceanFilteredBiomeSource filtered) {
            return new NoiseBasedChunkGenerator(filtered.inner(), noise.generatorSettings());
        }
        return inner;
    }

    private static Holder<DimensionType> dimensionTypeForDelegate(
            ChunkGenerator delegate,
            LevelStem overworld,
            LevelStem nether,
            LevelStem end
    ) {
        ResourceLocation settings = noiseSettings(delegate);
        if (settings == null) {
            return null;
        }
        if (settings.equals(ResourceLocation.withDefaultNamespace("overworld")) && overworld != null) {
            return overworld.type();
        }
        if (settings.equals(ResourceLocation.withDefaultNamespace("nether")) && nether != null) {
            return nether.type();
        }
        if (settings.equals(ResourceLocation.withDefaultNamespace("end")) && end != null) {
            return end.type();
        }
        return null;
    }

    private static ResourceKey<LevelStem> bandStemKey(ChunkGenerator delegate) {
        ResourceLocation settings = noiseSettings(delegate);
        if (settings == null) {
            return null;
        }
        String path;
        if (settings.equals(ResourceLocation.withDefaultNamespace("overworld"))) {
            if (delegate instanceof SlicedOverworldChunkGenerator sliced) {
                path = switch (sliced.slice()) {
                    case UNDERGROUND -> "rotating/overworld_caves";
                    case SURFACE -> "rotating/overworld";
                };
            } else {
                path = "rotating/overworld";
            }
        } else if (settings.equals(ResourceLocation.withDefaultNamespace("nether"))) {
            path = "rotating/the_nether";
        } else if (settings.equals(ResourceLocation.withDefaultNamespace("end"))) {
            path = "rotating/the_end";
        } else {
            return null;
        }
        return ResourceKey.create(Registries.LEVEL_STEM, ResourceLocation.fromNamespaceAndPath("dimblend", path));
    }

    private static ResourceLocation noiseSettings(ChunkGenerator delegate) {
        if (delegate instanceof SlicedOverworldChunkGenerator sliced) {
            delegate = sliced.inner();
        }
        if (!(delegate instanceof NoiseBasedChunkGenerator noise)) {
            return null;
        }
        return noise.generatorSettings().unwrapKey().map(ResourceKey::location).orElse(null);
    }
}
