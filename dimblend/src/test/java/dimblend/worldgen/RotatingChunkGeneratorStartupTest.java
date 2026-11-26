package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.TestSourceTree;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Startup-ordering contract for {@link RotatingChunkGenerator}'s delegate structure states.
 *
 * <p>The live crash ({@code EndBiomePlacement.sampleEndNoise}, chunk {@code 11281, -18}) came from
 * running the delegates' concentric-ring biome searches too early: a ring search over the End
 * delegate samples {@code TheEndBiomeSource} while Biolith's {@code BiomeCoordinator.END} noise is
 * still null, and the NPE poisons the ring future for the whole session. Everything here pins the
 * two guards — the pure readiness predicate and the fact that neither {@code createState} nor
 * {@code ensureRuntimeOrThrow} starts that pass — plus the {@code DimBlend} wiring that starts it
 * only after OVERWORLD/NETHER/END all exist.
 */
class RotatingChunkGeneratorStartupTest {
    /**
     * Only the readiness test loads the generator class itself, which pulls
     * {@code ChunkGenerator.CODEC} into {@code BuiltInRegistries} through its static initializer.
     * That needs FML's mod list to be non-null and the shared version detected; no registry content
     * is involved, so {@code Bootstrap.bootStrap()} is deliberately not run (it reads data files
     * missing from the test classpath).
     */
    @BeforeAll
    static void prepareMinecraftStatics() throws ReflectiveOperationException {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Field bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        bootstrapped.setBoolean(null, true);
    }

    /** Only the full three-dimension set may start the delegate pass; every partial set must not. */
    @Test
    void delegateStructuresWaitForAllThreeVanillaDimensions() {
        for (int mask = 0; mask < 8; mask++) {
            boolean overworld = (mask & 1) != 0;
            boolean nether = (mask & 2) != 0;
            boolean end = (mask & 4) != 0;
            assertEquals(
                    mask == 7,
                    RotatingChunkGenerator.readyToInitDelegateStructures(overworld, nether, end),
                    "overworld=" + overworld + " nether=" + nether + " end=" + end
                            + " must init only when all three dimensions exist");
        }
    }

    /**
     * {@code createState} runs inside the {@code ChunkMap} constructor, i.e. while
     * {@code createLevels()} is still building dimblend:rotating and possibly before
     * minecraft:the_end. It must therefore neither run the delegate pass nor hand back a main state
     * whose constructor-time {@code ensureStructuresGenerated()} would query {@code RotatingBiomeSource}.
     */
    @Test
    void createStateReturnsAnEmptyFlatStateAndDoesNotStartTheDelegatePass() throws Exception {
        String body = blockAt(source("java/dimblend/worldgen/RotatingChunkGenerator.java"),
                "ChunkGeneratorStructureState createState(");
        assertTrue(body.contains("createForFlat("),
                "the unused main state must be flat, or ServerLevel.<init> runs ring searches early");
        assertTrue(body.contains("Stream.empty()"),
                "the flat state must carry no structure sets");
        assertFalse(body.contains("createForNormal"),
                "createForNormal would make the constructor-time ensureStructuresGenerated() query biomes");
        assertFalse(body.contains("ensureDelegateStructuresGenerated"),
                "createState must not publish the delegate placement state during createLevels()");
    }

    /** {@code ensureRuntimeOrThrow} is the first touch on worker threads and during createLevels(). */
    @Test
    void ensureRuntimeOrThrowLeavesTheDelegatePassToTheStartupHooks() throws Exception {
        String generator = source("java/dimblend/worldgen/RotatingChunkGenerator.java");
        String body = blockAt(generator, "void ensureRuntimeOrThrow()");
        assertFalse(body.contains("ensureDelegateStructuresGenerated"),
                "early ensureRuntimeOrThrow() calls must not run ring searches before the End exists");
        assertTrue(generator.contains("private volatile boolean delegateStructuresGenerated;"),
                "the ready bit must be volatile so the fast path sees the hook's publication");

        String nearest = blockAt(generator, "Pair<BlockPos, Holder<Structure>> findNearestMapStructure(");
        assertTrue(nearest.contains("this.ensureDelegateStructuresGenerated();"),
                "structure lookups touch ring positions, so they must start/publish the delegate pass");
    }

    /**
     * The {@code LevelEvent.Load} at {@code LOWEST} fires inside {@code createLevels()} after the
     * vanilla dimensions finished {@code ServerLevel.<init>} (Biolith's per-dimension init included)
     * and before {@code prepareLevels()} forces rotating chunks. {@code ServerStartingEvent} is the
     * fallback for presets without NETHER/END.
     */
    @Test
    void dimBlendPublishesTheDelegatePassOnlyAfterTheVanillaDimensionsExist() throws Exception {
        String dimBlend = source("java/dimblend/DimBlend.java");
        assertTrue(dimBlend.contains(
                "addListener(EventPriority.LOWEST, DimBlend::onLevelLoadInitDelegateStructures)"),
                "the level-load hook must run at LOWEST, after every other dimension hook");
        assertTrue(dimBlend.contains("addListener(DimBlend::onServerStarting)"),
                "ServerStartingEvent must stay registered as the minimal-preset fallback");
        assertTrue(dimBlend.contains(
                "onLevelLoadInitDelegateStructures(LevelEvent.Load event)"),
                "the primary hook must be a LevelEvent.Load listener");

        String levelHook = blockAt(dimBlend, "void onLevelLoadInitDelegateStructures(");
        assertTrue(levelHook.contains("RotatingChunkGenerator.readyToInitDelegateStructures("),
                "the hook must gate on the readiness predicate");
        assertTrue(levelHook.contains("server.getLevel(Level.END) != null"),
                "the End must exist before the delegate ring searches run");

        String helper = blockAt(dimBlend, "void initRotatingDelegateStructures(");
        assertTrue(helper.contains("rotating.ensureDelegateStructuresGenerated()"),
                "only rotating generators get their delegate states published");

        String serverStarting = blockAt(dimBlend, "void onServerStarting(");
        assertTrue(serverStarting.contains("initRotatingDelegateStructures(event.getServer())"),
                "the fallback must reuse the idempotent helper");
    }

    private static String source(String relative) throws Exception {
        Path file = TestSourceTree.mainFile(relative);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Body (brace to brace) of the first method whose signature starts at {@code marker}. */
    private static String blockAt(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "missing declaration: " + marker);
        int brace = source.indexOf('{', at);
        assertTrue(brace >= 0, "missing body: " + marker);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(brace, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced body: " + marker);
    }
}
