package dimblend.spyglass.server;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.StructureNoiseSetup;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Everything the seed-only structure prediction needs, resolved on the server thread and read from the
 * scan executor afterwards.
 *
 * <p><b>Why this replays the real generator instead of re-implementing its rules.</b> The prediction
 * must produce exactly the {@code StructureStart} world generation would have produced in a chunk
 * nobody has visited. Doing that by hand would mean copying band routing, the weighted selection
 * inside a structure set, the no-structure zones and every future change to them. Instead this calls
 * the live {@link ChunkGenerator#createStructures} on a throwaway {@link ProtoChunk}, the same call
 * the real chunk pipeline makes ({@code ChunkGenerator.java:461-535} for the vanilla implementation,
 * {@code RotatingChunkGenerator.createStructures} for the banded one), so the dimension's own rules are
 * inherited rather than duplicated.
 *
 * <p><b>What the throwaway chunk means.</b> A {@link ProtoChunk} built with no sections,
 * {@link UpgradeData#EMPTY} and a plain {@link LevelHeightAccessor} holds no blocks and no biome data.
 * That is sufficient because structure placement never reads chunk contents: the start position comes
 * from the structure's height provider, the biome test samples the generator's {@code BiomeSource}
 * through the structure state's random state ({@code Structure.java:116-127}), jigsaw assembly
 * assembles templates, and the "is there already a start here" check reads the chunk's own start map,
 * which begins empty just like a fresh world-gen chunk at {@code STRUCTURE_STARTS}. Nothing loads a
 * chunk and nothing writes to the world.
 *
 * <p><b>Threading.</b> {@code createStructures} touches only the chunk it is given and the generator's
 * already-initialized per-band structure state, so it may run on the scan executor once
 * {@link dimblend.worldgen.StructureStateProvider#structureStatesFor} has run on the server thread
 * (see {@link StructureCandidates#bandsFor}). The shared {@link StructureManager} is used for exactly
 * two calls, {@code getStartForStructure} and {@code setStartForStructure}, both of which delegate to
 * the chunk handed in and never touch the manager's level ({@code StructureManager.java:84-90}); the
 * live manager is therefore safe to share with the server thread.
 */
final class PredictionContext {
    private final ResourceKey<Level> dimension;
    private final RegistryAccess registryAccess;
    private final ChunkGenerator generator;
    private final ChunkGeneratorStructureState mainState;
    private final StructureManager structureManager;
    private final StructureTemplateManager templateManager;
    private final LevelHeightAccessor heightAccessor;
    private final Registry<Biome> biomeRegistry;
    private final boolean generateStructures;

    PredictionContext(
            ResourceKey<Level> dimension,
            RegistryAccess registryAccess,
            ChunkGenerator generator,
            @Nullable ChunkGeneratorStructureState mainState,
            StructureManager structureManager,
            StructureTemplateManager templateManager,
            LevelHeightAccessor heightAccessor,
            boolean generateStructures) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.registryAccess = Objects.requireNonNull(registryAccess, "registryAccess");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.structureManager = Objects.requireNonNull(structureManager, "structureManager");
        this.templateManager = Objects.requireNonNull(templateManager, "templateManager");
        this.heightAccessor = Objects.requireNonNull(heightAccessor, "heightAccessor");
        this.generateStructures = generateStructures;
        // A banded generator resolves each delegate's structure state from the chunk's band itself;
        // only a plain generator needs the state vanilla would have passed in.
        this.mainState = generator instanceof RotatingChunkGenerator ? null : mainState;
        if (!(generator instanceof RotatingChunkGenerator) && this.mainState == null) {
            throw new IllegalArgumentException("A non-banded generator needs its structure state");
        }
        this.biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
    }

    ResourceKey<Level> dimension() {
        return this.dimension;
    }

    /** False when the level was created without structure generation: predicting would invent them. */
    boolean shouldGenerateStructures() {
        return this.generateStructures;
    }

    /**
     * Runs one chunk's structure placement into a fresh throwaway chunk and returns that chunk, so the
     * caller can read its starts. A chunk that simply produced no structure is not an error: a
     * structure whose generation fails is vanilla-legal and leaves no start behind.
     */
    ProtoChunk createStructures(ChunkPos pos) {
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, this.heightAccessor, this.biomeRegistry, null);
        // The banded generator opens this scope itself; a nested push is cheap and keeps a plain
        // generator on the same density-mapping path real world generation uses.
        StructureNoiseSetup.run(() -> {
            if (this.generator instanceof RotatingChunkGenerator rotating) {
                // The state argument is unused by the banded generator: it resolves the delegate and
                // that delegate's structure state from the chunk's own band.
                rotating.createStructures(
                        this.registryAccess, null, this.structureManager, chunk, this.templateManager);
            } else {
                this.generator.createStructures(
                        this.registryAccess,
                        this.mainState,
                        this.structureManager,
                        chunk,
                        this.templateManager);
            }
        });
        return chunk;
    }

    /** Maps a structure back to the id the whitelist and the client labels use. */
    @Nullable
    ResourceLocation idOf(Structure structure) {
        return this.registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(structure);
    }

    @Override
    public String toString() {
        return "PredictionContext[" + DimBlendSpyglass.MODID
                + " dimension=" + this.dimension.location()
                + " generateStructures=" + this.generateStructures
                + " generator=" + this.generator.getClass().getSimpleName() + "]";
    }
}