package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShape;
import dimblend.spyglass.structure.StructureShapeCodec;
import dimblend.spyglass.test.VanillaTestBootstrap;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

/** Bounds of a built mesh, including shapes that sit at the world coordinate limit. */
class BuiltShapeMeshTest {
    static {
        VanillaTestBootstrap.ensure();
    }

    @Test
    void growsTheShapeExtentByOneBlock() {
        OutlineBox bounds = BuiltShapeMesh.meshBounds(shape(10, 20, 30, 3, 4, 5));

        assertEquals(new OutlineBox(9, 19, 29, 13, 24, 35), bounds);
    }

    @Test
    void clampsTheGrownBoundsToTheWorldCoordinateLimit() {
        int limit = OutlineBox.MAX_COORDINATE;
        OutlineBox bounds = BuiltShapeMesh.meshBounds(shape(limit, -limit, limit - 2, 4, 4, 4));

        // Growing by one block must not leave the world: an unclamped box would throw here.
        assertEquals(new OutlineBox(limit - 1, -limit, limit - 3, limit, -limit + 4, limit), bounds);
    }

    private static StructureShape shape(
            int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {
        StructureShapeCodec.DecodedShape decoded = new StructureShapeCodec.DecodedShape(
                originX,
                originY,
                originZ,
                sizeX,
                sizeY,
                sizeZ,
                ResourceLocation.withDefaultNamespace("plains"),
                new int[] {0},
                new long[] {0L},
                new short[] {0});
        BlockState[] palette = {Blocks.STONE.defaultBlockState()};
        return StructureShape.of(
                new StructureKey(ResourceLocation.fromNamespaceAndPath("test", "house"), 0, 0),
                1,
                decoded,
                palette,
                Optional.empty());
    }
}
