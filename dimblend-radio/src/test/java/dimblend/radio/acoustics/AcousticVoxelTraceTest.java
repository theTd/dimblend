package dimblend.radio.acoustics;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticVoxelTraceTest {
    private static final double Y = 0.5, Z = 0.5;

    /** Solid cells at the given x positions along the ray row y=0, z=0. */
    private static AcousticVoxelTrace.Lookup row(VoxelShape shape, float reflectivity, int... xs) {
        var cell = new AcousticVoxelTrace.Cell(shape, reflectivity);
        Set<BlockPos> solid = new java.util.HashSet<>();
        for (int x : xs) solid.add(new BlockPos(x, 0, 0));
        return pos -> solid.contains(pos) ? cell : null;
    }

    private static Vec3 at(double x) { return new Vec3(x, Y, Z); }

    @Test void hitReportsTheEntryFaceAndThePathThroughTheWholeWall() {
        var lookup = row(Shapes.block(), 0.9f, 0, 1, 2);
        AcousticRay forward = AcousticVoxelTrace.cast(at(-3.5), at(5.5), lookup);
        assertEquals(AcousticRay.Kind.HIT, forward.kind());
        assertEquals(0, forward.position().x, 1e-9);
        assertEquals(new Vec3(-1, 0, 0), forward.normal());
        assertEquals(3, forward.thickness(), 1e-3);
        assertEquals(0.9f, forward.reflectivity());

        AcousticRay backward = AcousticVoxelTrace.cast(at(5.5), at(-3.5), lookup);
        assertEquals(3, backward.position().x, 1e-9);
        assertEquals(new Vec3(1, 0, 0), backward.normal());
        assertEquals(3, backward.thickness(), 1e-3);
    }

    @Test void aRayStartingInsideAWallSkipsItAndFindsTheNextOne() {
        // Steam Audio re-casts transmission rays from 1 cm past each hit, i.e. inside the wall.
        var lookup = row(Shapes.block(), 0.9f, 0, 2);
        AcousticRay next = AcousticVoxelTrace.cast(at(0.01), at(5.5), lookup);
        assertEquals(AcousticRay.Kind.HIT, next.kind(), "the second wall must still be found");
        assertEquals(2, next.position().x, 1e-9, "the wall the ray starts in must not be hit again");
        assertEquals(1, next.thickness(), 1e-3);

        assertEquals(AcousticRay.Kind.MISS, AcousticVoxelTrace.cast(at(2.01), at(5.5), lookup).kind(),
                "leaving the last wall reaches open air");
    }

    @Test void adjacentSolidBlocksAreOneRun() {
        var lookup = row(Shapes.block(), 0.9f, 0, 1, 2, 3);
        assertEquals(AcousticRay.Kind.MISS, AcousticVoxelTrace.cast(at(0.01), at(5.5), lookup).kind());
    }

    @Test void thicknessIsCappedAtTheModeledRange() {
        int[] xs = new int[20];
        for (int i = 0; i < xs.length; i++) xs[i] = i;
        var hit = AcousticVoxelTrace.cast(at(-1.5), at(30.5), row(Shapes.block(), 0.9f, xs));
        assertEquals(AcousticMaterials.MAX_THICKNESS, hit.thickness(), 1e-3);
    }

    @Test void partialShapesAreHitOnlyWhereTheyAreSolid() {
        var slab = row(Shapes.box(0, 0, 0, 1, 0.5, 1), 0.9f, 0);
        AcousticRay low = AcousticVoxelTrace.cast(new Vec3(-2, 0.25, Z), new Vec3(3, 0.25, Z), slab);
        assertEquals(AcousticRay.Kind.HIT, low.kind());
        assertEquals(1, low.thickness(), 1e-3);
        assertEquals(AcousticRay.Kind.MISS,
                AcousticVoxelTrace.cast(new Vec3(-2, 0.75, Z), new Vec3(3, 0.75, Z), slab).kind());
        AcousticRay rising = AcousticVoxelTrace.cast(new Vec3(0.2, 0.25, Z), new Vec3(1.2, 1.25, Z), slab);
        assertEquals(AcousticRay.Kind.MISS, rising.kind(), "a ray starting inside the slab skips it");
        // Leaving the slab through its top ends the run inside the voxel.
        AcousticRay diagonal = AcousticVoxelTrace.cast(new Vec3(-0.25, 0, Z), new Vec3(0.75, 1, Z), slab);
        assertEquals(AcousticRay.Kind.HIT, diagonal.kind());
        assertEquals(Math.sqrt(2) * 0.25, diagonal.thickness(), 1e-3);
    }

    @Test void unloadedTerrainIsUnknownNotOpenOrSolid() {
        AcousticVoxelTrace.Lookup lookup = pos -> pos.getX() == 2 ? AcousticVoxelTrace.Cell.UNKNOWN : null;
        AcousticRay ray = AcousticVoxelTrace.cast(at(-1.5), at(5.5), lookup);
        assertEquals(AcousticRay.Kind.UNKNOWN, ray.kind());
        assertEquals(2, ray.position().x, 1e-9);
    }
}
