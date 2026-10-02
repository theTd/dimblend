package dimblend.radio.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.radio.SubLevelProjection;
import dimblend.radio.acoustics.AcousticBlockMaterials;
import dimblend.radio.acoustics.AcousticMaterials;
import dimblend.radio.acoustics.AcousticRay;
import dimblend.radio.acoustics.AcousticRaycaster;
import dimblend.radio.acoustics.AcousticSnapshot;
import dimblend.radio.acoustics.AcousticSurfaceKinds;
import dimblend.radio.acoustics.AcousticVoxelTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dimblend_radio")
@PrefixGameTestTemplate(false)
public final class RadioAcousticsGameTests {
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void terrainCaveAndEmitterExclusion(GameTestHelper helper) {
        BlockPos source = buildRoom(helper);
        assertCave(helper, source);
        helper.succeed();
    }

    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void rotatedSableCaveAndWorldNormals(GameTestHelper helper) {
        BlockPos source = buildRoom(helper);
        BlockPos min = helper.absolutePos(BlockPos.ZERO);
        BlockPos max = helper.absolutePos(new BlockPos(4, 4, 4));
        var structure = SubLevelAssemblyHelper.assembleBlocks(helper.getLevel(), min,
                BlockPos.betweenClosed(min, max), new BoundingBox3i(min, max));
        helper.assertTrue(structure != null, "structure assembly must succeed");
        BlockPos plotSource = BlockPos.containing(structure.logicalPose()
                .transformPositionInverse(Vec3.atCenterOf(source)));
        helper.runAfterDelay(5, () -> {
            Vec3 center = SubLevelProjection.worldCenter(helper.getLevel(), plotSource);
            structure.logicalPose().orientation().rotateY(Math.PI / 2);
            Vec3 movedCenter = SubLevelProjection.worldCenter(helper.getLevel(), plotSource);
            Vec3 correction = center.subtract(movedCenter);
            structure.logicalPose().position().add(correction.x, correction.y, correction.z);
            structure.updateBoundingBox();
            try {
                assertCave(helper, plotSource);
                Vec3 from = SubLevelProjection.worldCenter(helper.getLevel(), plotSource);
                Vec3 direction = new Vec3(1, 0.2, 0.1).normalize();
                AcousticRay hit = new AcousticRaycaster(helper.getLevel(), plotSource)
                        .cast(from, from.add(direction.scale(16)));
                helper.assertTrue(hit.kind() == AcousticRay.Kind.HIT, "must hit rotated structure wall");
                helper.assertTrue(hit.position().distanceTo(from) < 4,
                        "hit must be in world space, not distant plot space");
                helper.assertTrue(hit.normal().dot(direction) < -0.8,
                        "normal must oppose incident world direction");
                Vec3 outside = from.add(12, 0, 0);
                AcousticRay entered = new AcousticRaycaster(helper.getLevel(), plotSource).cast(outside, from);
                helper.assertTrue(entered.kind() == AcousticRay.Kind.HIT && entered.normal().x > 0.9,
                        "a ray starting outside must enter and hit the structure in world space");
                BlockPos terrainWall = BlockPos.containing(from.add(8, 0, 0));
                helper.getLevel().setBlock(terrainWall, Blocks.STONE.defaultBlockState(), 3);
                try {
                    AcousticRay nearer = new AcousticRaycaster(helper.getLevel(), plotSource).cast(outside, from);
                    helper.assertTrue(nearer.kind() == AcousticRay.Kind.HIT
                                    && outside.distanceTo(nearer.position()) < outside.distanceTo(entered.position()),
                            "nearby terrain must win over a farther structure wall");
                } finally {
                    helper.getLevel().setBlock(terrainWall, Blocks.AIR.defaultBlockState(), 3);
                }
                helper.succeed();
            } finally {
                structure.markRemoved();
            }
        });
    }

    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void voxelFillerHonorsSkippedSections(GameTestHelper helper) {
        BlockPos source = buildRoom(helper);
        Vec3 origin = SubLevelProjection.worldCenter(helper.getLevel(), source);
        AABB bounds = new AABB(origin, origin).inflate(8);
        var full = new dimblend.radio.acoustics.AcousticMesh(Vec3.ZERO);
        full.append(helper.getLevel(), bounds, source);
        helper.assertTrue(full.data().triangles().length > 0, "room must mesh");
        // Skipping every section the room touches leaves no voxel faces at all.
        var all = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int x = (int) Math.floor(bounds.minX) >> 4; x <= ((int) Math.floor(bounds.maxX) >> 4); x++)
            for (int y = (int) Math.floor(bounds.minY) >> 4; y <= ((int) Math.floor(bounds.maxY) >> 4); y++)
                for (int z = (int) Math.floor(bounds.minZ) >> 4; z <= ((int) Math.floor(bounds.maxZ) >> 4); z++)
                    all.add(net.minecraft.core.SectionPos.asLong(x, y, z));
        var skipped = new dimblend.radio.acoustics.AcousticMesh(Vec3.ZERO);
        skipped.append(helper.getLevel(), bounds, source, all);
        helper.assertTrue(skipped.data().triangles().length == 0,
                "skipped sections must contribute no voxel faces");
        // A skip key outside the bounds must not change anything.
        var unrelated = new dimblend.radio.acoustics.AcousticMesh(Vec3.ZERO);
        unrelated.append(helper.getLevel(), bounds, source,
                it.unimi.dsi.fastutil.longs.LongSet.of(net.minecraft.core.SectionPos.asLong(
                        (int) Math.floor(bounds.minX) >> 4, ((int) Math.floor(bounds.minY) >> 4) + 16,
                        (int) Math.floor(bounds.minZ) >> 4)));
        helper.assertTrue(unrelated.data().triangles().length == full.data().triangles().length,
                "unrelated skip key must not change voxel geometry");
        helper.succeed();
    }

    /** Frozen palettes are read section by section; the result must equal the live per-cell fill. */
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void frozenSectionFillMatchesLiveCells(GameTestHelper helper) {
        BlockPos source = buildRoom(helper);
        Vec3 origin = SubLevelProjection.worldCenter(helper.getLevel(), source);
        AABB bounds = new AABB(origin, origin).inflate(20);
        var live = new dimblend.radio.acoustics.AcousticMesh(Vec3.ZERO);
        live.append(helper.getLevel(), bounds, source);
        var snapshot = AcousticSnapshot.capture(helper.getLevel(), bounds, source);
        var frozen = snapshot.terrainMesh(bounds, Vec3.ZERO, new dimblend.radio.acoustics.AcousticMesh.Workspace());
        var expected = live.data();
        helper.assertTrue(expected.triangleCount() > 0, "room must mesh");
        helper.assertTrue(java.util.Arrays.equals(expected.vertices(), frozen.vertices())
                        && java.util.Arrays.equals(expected.triangles(), frozen.triangles())
                        && java.util.Arrays.equals(expected.materials(), frozen.materials()),
                "frozen section fill must match the live cells: " + expected.triangleCount() + " vs " + frozen.triangleCount());
        helper.succeed();
    }

    /** The hardcoded material table against the real registries, tags and sound types. */
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void blockMaterialsFollowTagsAndSoundTypes(GameTestHelper helper) {
        expectMaterial(helper, AcousticMaterials.STONE, Blocks.STONE, Blocks.DEEPSLATE, Blocks.COBBLESTONE,
                Blocks.IRON_ORE, Blocks.GRANITE, Blocks.TUFF, Blocks.BRICKS, Blocks.WHITE_CONCRETE, Blocks.TERRACOTTA,
                Blocks.NETHERRACK);
        expectMaterial(helper, AcousticMaterials.SOIL, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.GRAVEL,
                Blocks.MUD, Blocks.SOUL_SOIL, Blocks.ROOTED_DIRT);
        expectMaterial(helper, AcousticMaterials.WOOD, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.CHERRY_PLANKS,
                Blocks.BAMBOO_PLANKS, Blocks.CRIMSON_PLANKS, Blocks.CRIMSON_STEM, Blocks.OAK_DOOR, Blocks.BOOKSHELF,
                Blocks.JUKEBOX);
        expectMaterial(helper, AcousticMaterials.WOOL, Blocks.WHITE_WOOL, Blocks.WHITE_CARPET, Blocks.RED_BED,
                Blocks.HAY_BLOCK, Blocks.MOSS_BLOCK, Blocks.SPONGE);
        expectMaterial(helper, AcousticMaterials.FOLIAGE, Blocks.OAK_LEAVES, Blocks.AZALEA_LEAVES, Blocks.CHERRY_LEAVES);
        expectMaterial(helper, AcousticMaterials.GLASS, Blocks.GLASS, Blocks.GLASS_PANE, Blocks.WHITE_STAINED_GLASS,
                Blocks.TINTED_GLASS);
        expectMaterial(helper, AcousticMaterials.METAL, Blocks.IRON_BLOCK, Blocks.COPPER_BLOCK, Blocks.ANVIL,
                Blocks.IRON_DOOR, Blocks.NETHERITE_BLOCK);
        expectMaterial(helper, AcousticMaterials.ICE, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE);
        expectMaterial(helper, AcousticMaterials.SNOW, Blocks.SNOW_BLOCK, Blocks.SNOW);
        // Both geometry paths read the same table: the mesher's surface kind and the tracer's cell.
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.GLASS);
        var state = helper.getLevel().getBlockState(pos);
        helper.assertTrue(AcousticSurfaceKinds.of(state, helper.getLevel(), pos) == AcousticMaterials.GLASS + 1,
                "mesh surface kind is the material plus one");
        helper.assertTrue(AcousticVoxelTrace.cell(state, helper.getLevel(), pos).material() == AcousticMaterials.GLASS,
                "voxel cell carries the material");
        helper.succeed();
    }

    private static void expectMaterial(GameTestHelper helper, int material, Block... blocks) {
        for (Block block : blocks) {
            int actual = AcousticBlockMaterials.of(block.defaultBlockState());
            helper.assertTrue(actual == material, block + " should be material " + material + ", was " + actual);
        }
    }

    private static BlockPos buildRoom(GameTestHelper helper) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(4, 4, 4))) {
            if (pos.getX() == 0 || pos.getX() == 4 || pos.getY() == 0 || pos.getY() == 4
                    || pos.getZ() == 0 || pos.getZ() == 4) {
                helper.setBlock(pos, Blocks.STONE);
            }
        }
        BlockPos source = new BlockPos(2, 2, 2);
        // Keep the emitter connected so Sable does not split it into a different plot.
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
        helper.setBlock(source, Blocks.JUKEBOX);
        return helper.absolutePos(source);
    }

    private static void assertCave(GameTestHelper helper, BlockPos source) {
        Vec3 origin = SubLevelProjection.worldCenter(helper.getLevel(), source);
        var tracer = new AcousticRaycaster(helper.getLevel(), source);
        var snapshot = AcousticSnapshot.capture(helper.getLevel(), origin, source);
        var mesh = snapshot.mesh(origin, origin);
        for (int i = 0; i < 24; i++) {
            double y = 1 - 2 * (i + 0.5) / 24;
            double radius = Math.sqrt(1 - y * y);
            double angle = i * Math.PI * (3 - Math.sqrt(5));
            Vec3 end = origin.add(Math.cos(angle) * radius * 64, y * 64, Math.sin(angle) * radius * 64);
            AcousticRay live = tracer.cast(origin, end);
            AcousticRay frozen = snapshot.cast(origin, end);
            helper.assertTrue(live.kind() == AcousticRay.Kind.HIT && frozen.kind() == AcousticRay.Kind.HIT,
                    "both live and immutable scene rays must hit the closed room");
            helper.assertTrue(live.position().distanceTo(frozen.position()) < 0.001,
                    "snapshot hit must match live terrain/structure geometry");
            double meshDistance = meshDistance(mesh, origin, end.subtract(origin).normalize());
            helper.assertTrue(Math.abs(meshDistance - origin.distanceTo(live.position())) < 0.002,
                    "GPU mesh must match terrain/rotated Sable room and exclude emitter");
        }
        AcousticRay ray = tracer.cast(origin, origin.add(16, 0, 0));
        helper.assertTrue(ray.kind() == AcousticRay.Kind.HIT, "must hit wall");
        helper.assertTrue(origin.distanceTo(ray.position()) > 1,
                "emitter's own jukebox must not count as an enclosing wall");
    }

    private static double meshDistance(dimblend.radio.acoustics.AcousticMesh.Data mesh, Vec3 from, Vec3 direction) {
        Vec3 local = from.subtract(mesh.origin());
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < mesh.triangles().length; i += 3) {
            Vec3 a = vertex(mesh, mesh.triangles()[i]);
            Vec3 ab = vertex(mesh, mesh.triangles()[i + 1]).subtract(a);
            Vec3 ac = vertex(mesh, mesh.triangles()[i + 2]).subtract(a);
            Vec3 cross = direction.cross(ac);
            double determinant = ab.dot(cross);
            if (Math.abs(determinant) < 1e-8) continue;
            Vec3 delta = local.subtract(a);
            double u = delta.dot(cross) / determinant;
            Vec3 q = delta.cross(ab);
            double v = direction.dot(q) / determinant;
            double distance = ac.dot(q) / determinant;
            if (u >= -1e-6 && v >= -1e-6 && u + v <= 1.000001 && distance > 0.001)
                nearest = Math.min(nearest, distance);
        }
        return nearest;
    }

    private static Vec3 vertex(dimblend.radio.acoustics.AcousticMesh.Data mesh, int index) {
        return new Vec3(mesh.vertices()[index * 3], mesh.vertices()[index * 3 + 1], mesh.vertices()[index * 3 + 2]);
    }

    private RadioAcousticsGameTests() { }
}
