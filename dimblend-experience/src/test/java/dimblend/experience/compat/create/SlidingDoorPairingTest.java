package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

class SlidingDoorPairingTest {
    private static final double EPS = 1.0e-7;

    @Test
    void targetDoorWhitelistIncludesOnlyTheFourDoorsAndExcludesFramedGlass() {
        assertEquals(4, SlidingDoorPairing.targetDoorIds().size());
        for (String id : List.of(
                "create:andesite_door",
                "create:brass_door",
                "create:copper_door",
                "create:train_door")) {
            assertTrue(SlidingDoorPairing.isTargetDoorId(id), "must include " + id);
            assertTrue(SlidingDoorPairing.isTargetDoorId(ResourceLocation.parse(id)), "must include RL " + id);
        }

        assertFalse(SlidingDoorPairing.isTargetDoorId("create:framed_glass_door"));
        assertFalse(SlidingDoorPairing.isTargetDoorId(ResourceLocation.parse("create:framed_glass_door")));
        assertFalse(SlidingDoorPairing.isTargetDoorId("minecraft:iron_door"));
        assertFalse(SlidingDoorPairing.isTargetDoorId("minecraft:oak_door"));
        assertFalse(SlidingDoorPairing.isTargetDoorId((String) null));
        assertFalse(SlidingDoorPairing.isTargetDoorId((ResourceLocation) null));
    }

    @Test
    void closedPairedShapesAreTwoWideCenteredAcrossAllFourFacings() {
        assertBox(SlidingDoorPairing.closedPairedShape(Direction.SOUTH),
                -0.5, 0.0, 0.0, 1.5, 1.0, 3.0 / 16.0);
        assertBox(SlidingDoorPairing.closedPairedShape(Direction.NORTH),
                -0.5, 0.0, 13.0 / 16.0, 1.5, 1.0, 1.0);
        assertBox(SlidingDoorPairing.closedPairedShape(Direction.EAST),
                0.0, 0.0, -0.5, 3.0 / 16.0, 1.0, 1.5);
        assertBox(SlidingDoorPairing.closedPairedShape(Direction.WEST),
                13.0 / 16.0, 0.0, -0.5, 1.0, 1.0, 1.5);
    }

    @Test
    void openPairedShapesFormTwoSymmetricOutwardLeavesForSlidingAndFoldingDoors() {
        // Non-folding (brass_door, train_door): leaves slide outward to [-21/16, -5/16] and [21/16, 37/16]
        VoxelShape southSliding = SlidingDoorPairing.openPairedShape(Direction.SOUTH, false);
        assertEquals(2, southSliding.toAabbs().size());
        assertEquals(-21.0 / 16.0, southSliding.min(Direction.Axis.X), EPS);
        assertEquals(37.0 / 16.0, southSliding.max(Direction.Axis.X), EPS);
        assertEquals(0.0, southSliding.min(Direction.Axis.Z), EPS);
        assertEquals(3.0 / 16.0, southSliding.max(Direction.Axis.Z), EPS);

        VoxelShape northSliding = SlidingDoorPairing.openPairedShape(Direction.NORTH, false);
        assertEquals(2, northSliding.toAabbs().size());
        assertEquals(-21.0 / 16.0, northSliding.min(Direction.Axis.X), EPS);
        assertEquals(37.0 / 16.0, northSliding.max(Direction.Axis.X), EPS);
        assertEquals(13.0 / 16.0, northSliding.min(Direction.Axis.Z), EPS);
        assertEquals(1.0, northSliding.max(Direction.Axis.Z), EPS);

        VoxelShape eastSliding = SlidingDoorPairing.openPairedShape(Direction.EAST, false);
        assertEquals(2, eastSliding.toAabbs().size());
        assertEquals(-21.0 / 16.0, eastSliding.min(Direction.Axis.Z), EPS);
        assertEquals(37.0 / 16.0, eastSliding.max(Direction.Axis.Z), EPS);
        assertEquals(0.0, eastSliding.min(Direction.Axis.X), EPS);
        assertEquals(3.0 / 16.0, eastSliding.max(Direction.Axis.X), EPS);

        VoxelShape westSliding = SlidingDoorPairing.openPairedShape(Direction.WEST, false);
        assertEquals(2, westSliding.toAabbs().size());
        assertEquals(-21.0 / 16.0, westSliding.min(Direction.Axis.Z), EPS);
        assertEquals(37.0 / 16.0, westSliding.max(Direction.Axis.Z), EPS);
        assertEquals(13.0 / 16.0, westSliding.min(Direction.Axis.X), EPS);
        assertEquals(1.0, westSliding.max(Direction.Axis.X), EPS);

        // Folding (andesite_door, copper_door): leaves fold outward to [-11/16, -5/16] and [21/16, 27/16]
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape folding = SlidingDoorPairing.openPairedShape(facing, true);
            List<AABB> boxes = folding.toAabbs();
            assertEquals(2, boxes.size(), "folding open door must have 2 disjoint leaves for " + facing);
            Direction.Axis widthAxis = facing.getClockWise().getAxis();
            assertEquals(-11.0 / 16.0, folding.min(widthAxis), EPS);
            assertEquals(27.0 / 16.0, folding.max(widthAxis), EPS);
            assertEquals(0.5, 0.5 * (folding.min(widthAxis) + folding.max(widthAxis)), EPS);
        }
    }

    @Test
    void overhangHitLocationClampStaysWithinVanillaUseItemOnPacketTolerance() {
        BlockPos doorPos = new BlockPos(10, 64, -20);
        Vec3 center = doorPos.getCenter();

        // Point within [-0.999, +0.999] on a closed overhang stays untouched
        Vec3 insideOverhang = new Vec3(center.x + 0.92, center.y + 0.3, center.z - 0.4);
        Vec3 kept = SlidingDoorPairing.clampHitLocationToBlockCenter(insideOverhang, doorPos);
        assertEquals(insideOverhang.x, kept.x, EPS);
        assertEquals(insideOverhang.y, kept.y, EPS);
        assertEquals(insideOverhang.z, kept.z, EPS);

        // Outer edge of closed overhang (+1.0 from center) and open sliding leaf (+1.8125 / -1.8125)
        Vec3 farOverhang = new Vec3(center.x + 1.8125, center.y - 1.25, center.z - 1.0);
        Vec3 clamped = SlidingDoorPairing.clampHitLocationToBlockCenter(farOverhang, doorPos);
        assertEquals(center.x + 0.999, clamped.x, 1.0e-9);
        assertEquals(center.y - 0.999, clamped.y, 1.0e-9);
        assertEquals(center.z - 0.999, clamped.z, 1.0e-9);

        Vec3 diff = clamped.subtract(center);
        assertTrue(Math.abs(diff.x) < 1.0000001);
        assertTrue(Math.abs(diff.y) < 1.0000001);
        assertTrue(Math.abs(diff.z) < 1.0000001);

        // Also check Sable plotyard coordinates (~30,000,000 blocks from origin)
        BlockPos plotPos = new BlockPos(30_000_128, 72, -30_000_256);
        Vec3 plotCenter = plotPos.getCenter();
        Vec3 plotClamped = SlidingDoorPairing.clampHitLocationToBlockCenter(
                new Vec3(plotCenter.x - 1.75, plotCenter.y + 0.25, plotCenter.z + 1.5), plotPos);
        Vec3 plotDiff = plotClamped.subtract(plotCenter);
        assertTrue(Math.abs(plotDiff.x) < 1.0000001);
        assertTrue(Math.abs(plotDiff.y) < 1.0000001);
        assertTrue(Math.abs(plotDiff.z) < 1.0000001);
    }

    @Test
    void segmentDistanceHelperHandlesDegenerateAndDiagonalRays() {
        assertEquals(0.0, SlidingDoorOverhangPick.distSqPointToSegment2d(2.0, 3.0, 0.0, 3.0, 5.0, 3.0), EPS);
        assertEquals(2.25, SlidingDoorOverhangPick.distSqPointToSegment2d(2.0, 4.5, 0.0, 3.0, 5.0, 3.0), EPS);
        assertEquals(5.0, SlidingDoorOverhangPick.distSqPointToSegment2d(3.0, 4.0, 1.0, 3.0, 1.0, 3.0), EPS);
    }

    @Test
    void overhangPickVerifiesCleanlyWhenSableIsAbsentFromClassLoader() throws Exception {
        String resource = "dimblend/experience/compat/create/SlidingDoorOverhangPick.class";
        byte[] classBytes;
        try (var stream = SlidingDoorPairingTest.class.getClassLoader().getResourceAsStream(resource)) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream, "SlidingDoorOverhangPick.class must exist");
            classBytes = stream.readAllBytes();
        }
        ClassLoader loaderWithoutSable = new ClassLoader(SlidingDoorPairingTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.ryanhcode.sable.")) {
                    throw new ClassNotFoundException("Sable intentionally absent: " + name);
                }
                if ("dimblend.experience.compat.create.SlidingDoorOverhangPick".equals(name)) {
                    Class<?> defined = defineClass(name, classBytes, 0, classBytes.length);
                    if (resolve) {
                        resolveClass(defined);
                    }
                    return defined;
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> verified = Class.forName(
                "dimblend.experience.compat.create.SlidingDoorOverhangPick", true, loaderWithoutSable);
        org.junit.jupiter.api.Assertions.assertNotNull(verified.getDeclaredMethod(
                "updateCrosshairPick", net.minecraft.client.Minecraft.class, float.class));
    }

    private static void assertBox(
            VoxelShape shape,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        assertEquals(1, shape.toAabbs().size());
        assertEquals(minX, shape.min(Direction.Axis.X), EPS);
        assertEquals(minY, shape.min(Direction.Axis.Y), EPS);
        assertEquals(minZ, shape.min(Direction.Axis.Z), EPS);
        assertEquals(maxX, shape.max(Direction.Axis.X), EPS);
        assertEquals(maxY, shape.max(Direction.Axis.Y), EPS);
        assertEquals(maxZ, shape.max(Direction.Axis.Z), EPS);
    }
}
