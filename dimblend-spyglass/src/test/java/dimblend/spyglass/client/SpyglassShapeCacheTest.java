package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import dimblend.spyglass.structure.StructureShape;
import dimblend.spyglass.structure.StructureShapeCodec;
import dimblend.spyglass.test.VanillaTestBootstrap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the client shape cache's eviction policy with small block budgets through the
 * package-private {@code store} seam; the wire decoding is covered by {@code ShapePartAssemblerTest}
 * and the production path only adds the snapshot membership predicate.
 */
class SpyglassShapeCacheTest {
    static {
        VanillaTestBootstrap.ensure();
    }

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "house");
    private static final ResourceLocation BIOME = ResourceLocation.withDefaultNamespace("plains");
    private static final OutlineBox BOX = new OutlineBox(0, 0, 0, 0, 0, 0);

    @BeforeEach
    void reset() {
        SpyglassShapeCache.clear();
    }

    @Test
    void evictsShapesOutsideTheCurrentSnapshotFirst() {
        StructureKey inA = key("in_a");
        StructureKey inB = key("in_b");
        StructureKey outA = key("out_a");
        StructureKey outB = key("out_b");
        Predicate<StructureKey> inSnapshot = key -> key.equals(inA) || key.equals(inB);
        int budget = 10;

        SpyglassShapeCache.store(shape(outA, 1, 3), budget, inSnapshot);
        SpyglassShapeCache.store(shape(outB, 1, 3), budget, inSnapshot);
        SpyglassShapeCache.store(shape(inA, 1, 3), budget, inSnapshot);
        // 12 blocks: the least recently used out-of-snapshot shape goes, not a snapshot one.
        SpyglassShapeCache.store(shape(inB, 1, 3), budget, inSnapshot);

        assertTrue(SpyglassShapeCache.shapeFor(outline(outA)).isEmpty());
        assertTrue(SpyglassShapeCache.shapeFor(outline(outB)).isPresent());
        assertTrue(SpyglassShapeCache.shapeFor(outline(inA)).isPresent());
        assertTrue(SpyglassShapeCache.shapeFor(outline(inB)).isPresent());
        assertEquals(9, SpyglassShapeCache.cachedBlocks());
        assertEquals(3, SpyglassShapeCache.cachedShapeCount());
    }

    @Test
    void rejectsTheNewcomerWhenOnlySnapshotShapesRemain() {
        StructureKey first = key("first");
        StructureKey second = key("second");
        Predicate<StructureKey> everythingInSnapshot = key -> true;
        int budget = 10;

        SpyglassShapeCache.store(shape(first, 1, 6), budget, everythingInSnapshot);
        // Both are in the snapshot: the newcomer is rejected instead of evicting the cached shape.
        SpyglassShapeCache.store(shape(second, 1, 6), budget, everythingInSnapshot);

        assertTrue(SpyglassShapeCache.shapeFor(outline(first)).isPresent());
        assertTrue(SpyglassShapeCache.shapeFor(outline(second)).isEmpty());
        assertEquals(6, SpyglassShapeCache.cachedBlocks());
        assertEquals(1, SpyglassShapeCache.cachedShapeCount());
    }

    @Test
    void aSameRevisionReceiptOnlyRefreshesItsRecency() {
        StructureKey a = key("a");
        StructureKey b = key("b");
        StructureKey c = key("c");
        StructureKey d = key("d");
        Predicate<StructureKey> nothingInSnapshot = key -> false;
        int budget = 9;

        StructureShape original = shape(a, 1, 3);
        SpyglassShapeCache.store(original, budget, nothingInSnapshot);
        SpyglassShapeCache.store(shape(b, 1, 3), budget, nothingInSnapshot);
        SpyglassShapeCache.store(shape(c, 1, 3), budget, nothingInSnapshot);

        // Same key and revision again: same instance kept, only the LRU position moves.
        SpyglassShapeCache.store(shape(a, 1, 3), budget, nothingInSnapshot);
        assertSame(original, SpyglassShapeCache.shapeFor(outline(a)).orElseThrow());
        assertEquals(9, SpyglassShapeCache.cachedBlocks());
        assertEquals(3, SpyglassShapeCache.cachedShapeCount());

        // A is now the most recently used, so inserting D evicts B, not A.
        SpyglassShapeCache.store(shape(d, 1, 3), budget, nothingInSnapshot);
        assertTrue(SpyglassShapeCache.shapeFor(outline(a)).isPresent());
        assertTrue(SpyglassShapeCache.shapeFor(outline(b)).isEmpty());
        assertTrue(SpyglassShapeCache.shapeFor(outline(c)).isPresent());
        assertTrue(SpyglassShapeCache.shapeFor(outline(d)).isPresent());
        assertEquals(9, SpyglassShapeCache.cachedBlocks());
    }

    private static StructureKey key(String path) {
        return new StructureKey(ResourceLocation.fromNamespaceAndPath("test", path), 0, 0);
    }

    private static StructureOutline outline(StructureKey key) {
        return StructureOutline.of(key, List.of(BOX));
    }

    private static StructureShape shape(StructureKey key, int revision, int blocks) {
        long[] positions = new long[blocks];
        for (int i = 0; i < blocks; i++) {
            positions[i] = i;
        }
        StructureShapeCodec.DecodedShape decoded = new StructureShapeCodec.DecodedShape(
                0,
                0,
                0,
                blocks,
                1,
                1,
                BIOME,
                new int[] {0},
                positions,
                new short[blocks]);
        BlockState[] palette = {Blocks.STONE.defaultBlockState()};
        return StructureShape.of(key, revision, decoded, palette, Optional.empty());
    }
}
