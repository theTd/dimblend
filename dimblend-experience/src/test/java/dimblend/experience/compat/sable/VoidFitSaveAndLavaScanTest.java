package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A1 保存脏标记守卫 + A2 载具级岩浆预筛的数学口径。
 *
 * <p>脏标记（见 {@link VoidFitApplier}）：干净区块上的干燥 AIR ↔ STRUCTURE_VOID
 * 都只是内存临时态（保存时被 {@link VoidFitStrip} 剥回空气、磁盘原本就是空气），
 * 不该把区块标脏；真实方块改动、流体驱逐、贴流体拆除都必须保持脏。</p>
 *
 * <p>预筛（见 {@link LavaContactScan#vehicleScanBounds}）：接触扫描按每个方块的
 * "中心 ± 保守半 extent ± 容差"取格，柱顶再多收一格。载具级块盒由 plot 盒（已含每个
 * 方块自身体积）按当前 pose 变换后的 8 角 AABB 外扩容差得出，必须是每个方块扫描盒的
 * 严格超集——否则预筛会把 section 当"无岩浆"跳过，车顶的岩浆浇淋就永远不发生。
 * 本测试按 {@code Pose3dc#transformPosition}（{@code position + R·((p - rotationPoint) ∘ scale)}）
 * 与 {@code VoidFitVoxelizer} 的半 extent 公式（{@code |R|·scale/2}）逐方块重算并对比。</p>
 */
class VoidFitSaveAndLavaScanTest {

    private static final double TOL = LavaMeltMath.CONTACT_TOLERANCE;
    private static final int MIN_Y = -64;
    /** 世界最高层（不含）：合法格 y ≤ {@code MAX_Y - 1}。 */
    private static final int MAX_Y = 320;

    // ---------------------------------------------------------------- A1: 脏标记

    @Test
    void cleanChunkDirtyFlagSurvivesOnlyEphemeralDryVoidWrites() {
        assertTrue(VoidFitApplier.shouldPreserveCleanChunkOnPlace(false, false),
                "clean chunk + dry AIR->VOID never changes disk: keep it clean");
        assertFalse(VoidFitApplier.shouldPreserveCleanChunkOnPlace(false, true),
                "evicting a fluid is a real world change: must stay dirty");
        assertFalse(VoidFitApplier.shouldPreserveCleanChunkOnPlace(true, false),
                "a chunk already dirty carries someone else's real edit: never clear");
        assertFalse(VoidFitApplier.shouldPreserveCleanChunkOnPlace(true, true));
    }

    @Test
    void cleanChunkDirtyFlagSurvivesOnlyDryTrailRemoval() {
        assertTrue(VoidFitApplier.shouldPreserveCleanChunkOnRemove(false, false),
                "clean chunk + dry VOID->AIR never changes disk: keep it clean");
        assertFalse(VoidFitApplier.shouldPreserveCleanChunkOnRemove(false, true),
                "neighbour fluid means REMOVE_FLAGS wakes up a real flow: must stay dirty");
        assertFalse(VoidFitApplier.shouldPreserveCleanChunkOnRemove(true, false),
                "a chunk already dirty carries someone else's real edit: never clear");
        assertFalse(VoidFitApplier.shouldPreserveCleanChunkOnRemove(true, true));
    }

    @Test
    void chunkMapStripMixinWrapsChunkSerializerWriteWithFinallyAndPlotGuard() throws Exception {
        java.nio.file.Path moduleRoot = java.nio.file.Path.of("").toAbsolutePath();
        if (!java.nio.file.Files.exists(moduleRoot.resolve("src/main/resources/dimblend_experience.mixins.json"))) {
            moduleRoot = moduleRoot.resolve("dimblend-experience");
        }
        String mixinSrc = java.nio.file.Files.readString(
                moduleRoot.resolve("src/main/java/dimblend/experience/mixin/ChunkMapVoidFitStripMixin.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(mixinSrc.contains("@WrapOperation"),
                "ChunkMap#save has 5 ireturns; must wrap the single ChunkSerializer.write call instead of @At(RETURN)");
        assertTrue(mixinSrc.contains("ChunkSerializer;write("),
                "must target ChunkSerializer.write inside ChunkMap#save");
        assertTrue(mixinSrc.contains("require = 1") && mixinSrc.contains("allow = 1"),
                "injector must pin require = 1, allow = 1");
        assertTrue(mixinSrc.contains("finally") && mixinSrc.contains("dimblend$isLoaded()"),
                "restore must run in finally and skip unloaded chunks");

        String applierSrc = java.nio.file.Files.readString(
                moduleRoot.resolve("src/main/java/dimblend/experience/compat/sable/VoidFitApplier.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(applierSrc.contains("getChunkHolder(chunk.getPos()) != null"),
                "VoidFitApplier must explicitly exclude Sable plot chunks before writing or clearing unsaved");
    }

    // ------------------------------------------------------------ A2: 预筛块盒

    /** plot 内方块坐标盒（含端点），与 {@code LevelPlot#getBoundingBox()} 同口径。 */
    private record PlotBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    /** pose 三要素：{@code transformPosition = position + R·((p - rotationPoint) ∘ scale)}。 */
    private record Pose(double[] position, double[] quaternion, double[] scale, double[] rotationPoint) {
    }

    @Test
    void vehicleScanBoundsContainEveryProjectedBlockScanBox() {
        PlotBox plot = new PlotBox(-5, -2, 3, 4, 6, 9);
        // 单位 pose（无旋转缩放）
        assertCovers(plot, pose(0, 0, 0, 0, 0, 0, 1, 1, 1, 1));
        // 绕 Y 30°：水平保守覆盖跨 1-2 格
        assertCovers(plot, pose(-37.3, 71.75, 12.125,
                0, Math.sin(Math.toRadians(15)), 0, Math.cos(Math.toRadians(15)), 1, 1, 1));
        // 任意旋转 + 非均匀缩放 + 非零旋转点 + 负坐标
        assertCovers(plot, pose(1024.75, -59.5, -2048.25, 0.3, -0.5, 0.7, 0.4,
                0.5, 1.5, 2.0, 2.5, 1.5, 3.5));
        // 均匀放大 2 倍、亚格偏移、跨区块边界
        PlotBox wide = new PlotBox(-17, 60, 15, 18, 63, 33);
        assertCovers(wide, pose(0.5, 0.5, 0.5, 0.25, 0.25, 0.25, 0.9, 2, 2, 2));
        // 贴世界底/顶：钳制后仍是超集
        PlotBox tall = new PlotBox(-2, -12, 3, 4, 12, 9);
        assertCovers(tall, pose(0, -60, 0, 0, 0, 0, 1, 3, 3, 3));
        assertCovers(tall, pose(0, 316, 0, 0.5, 0.5, 0.5, 0.5, 4, 4, 4));
    }

    @Test
    void vehicleScanBoundsClampToWorldHeight() {
        LavaContactScan.BlockBox tall = LavaContactScan.vehicleScanBounds(
                0, -1000, 0, 0, 5000, 0, TOL, MIN_Y, MAX_Y);
        assertEquals(MIN_Y, tall.minY());
        assertEquals(MAX_Y - 1, tall.maxY());
        assertFalse(tall.isEmpty());

        // 整段载具在世界底部之下：盒空 → 调用方直接跳过（与逐格空循环等价）
        LavaContactScan.BlockBox below = LavaContactScan.vehicleScanBounds(
                0, -200, 0, 0, -150, 0, TOL, MIN_Y, MAX_Y);
        assertTrue(below.isEmpty());

        // 整段载具在世界顶部之上
        LavaContactScan.BlockBox above = LavaContactScan.vehicleScanBounds(
                0, 400, 0, 3, 410, 3, TOL, MIN_Y, MAX_Y);
        assertTrue(above.isEmpty());
    }

    /** 逐方块重算接触扫描盒，断言载具级盒完整覆盖（含柱顶 +1 格）。 */
    private static void assertCovers(PlotBox plot, Pose pose) {
        LavaContactScan.BlockBox bounds = vehicleBounds(plot, pose);
        double[] r = rotation(pose.quaternion());
        double halfX = (Math.abs(r[0]) * pose.scale()[0]
                + Math.abs(r[1]) * pose.scale()[1]
                + Math.abs(r[2]) * pose.scale()[2]) / 2.0D;
        double halfY = (Math.abs(r[3]) * pose.scale()[0]
                + Math.abs(r[4]) * pose.scale()[1]
                + Math.abs(r[5]) * pose.scale()[2]) / 2.0D;
        double halfZ = (Math.abs(r[6]) * pose.scale()[0]
                + Math.abs(r[7]) * pose.scale()[1]
                + Math.abs(r[8]) * pose.scale()[2]) / 2.0D;
        for (int px = (int) plot.minX(); px <= (int) plot.maxX(); px++) {
            for (int py = (int) plot.minY(); py <= (int) plot.maxY(); py++) {
                for (int pz = (int) plot.minZ(); pz <= (int) plot.maxZ(); pz++) {
                    double[] world = transform(pose, r, px + 0.5D, py + 0.5D, pz + 0.5D);
                    String where = "block (" + px + "," + py + "," + pz + ")";
                    assertCovered(bounds.minX(), bounds.maxX(),
                            (int) Math.floor(world[0] - halfX - TOL),
                            (int) Math.floor(world[0] + halfX + TOL), where + " x");
                    assertCovered(bounds.minZ(), bounds.maxZ(),
                            (int) Math.floor(world[2] - halfZ - TOL),
                            (int) Math.floor(world[2] + halfZ + TOL), where + " z");
                    int y0 = LavaContactScan.scanMinY(world[1], halfY, TOL, MIN_Y);
                    int y1 = LavaContactScan.scanMaxY(world[1], halfY, TOL, MAX_Y - 1, true);
                    assertCovered(bounds.minY(), bounds.maxY(), y0, y1, where + " y (column top)");
                }
            }
        }
    }

    private static void assertCovered(int boxMin, int boxMax, int cellMin, int cellMax, String what) {
        if (cellMin > cellMax) {
            return; // 该轴无合法格（世界高度钳制后为空），无覆盖义务
        }
        assertTrue(boxMin <= cellMin, what + ": pre-filter min " + boxMin + " must include " + cellMin);
        assertTrue(boxMax >= cellMax, what + ": pre-filter max " + boxMax + " must include " + cellMax);
    }

    /** 载具世界 AABB（plot 盒 8 角按 pose 变换后的包围盒）→ 预筛块盒。 */
    private static LavaContactScan.BlockBox vehicleBounds(PlotBox plot, Pose pose) {
        double[] r = rotation(pose.quaternion());
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double cx = (corner & 1) == 0 ? plot.minX() : plot.maxX() + 1.0D;
            double cy = (corner & 2) == 0 ? plot.minY() : plot.maxY() + 1.0D;
            double cz = (corner & 4) == 0 ? plot.minZ() : plot.maxZ() + 1.0D;
            double[] world = transform(pose, r, cx, cy, cz);
            minX = Math.min(minX, world[0]);
            minY = Math.min(minY, world[1]);
            minZ = Math.min(minZ, world[2]);
            maxX = Math.max(maxX, world[0]);
            maxY = Math.max(maxY, world[1]);
            maxZ = Math.max(maxZ, world[2]);
        }
        return LavaContactScan.vehicleScanBounds(minX, minY, minZ, maxX, maxY, maxZ,
                TOL, MIN_Y, MAX_Y);
    }

    /** pose.transformPosition：先减旋转点、按轴缩放、旋转，再加位置。 */
    private static double[] transform(Pose pose, double[] r, double x, double y, double z) {
        double px = (x - pose.rotationPoint()[0]) * pose.scale()[0];
        double py = (y - pose.rotationPoint()[1]) * pose.scale()[1];
        double pz = (z - pose.rotationPoint()[2]) * pose.scale()[2];
        return new double[] {
                pose.position()[0] + r[0] * px + r[1] * py + r[2] * pz,
                pose.position()[1] + r[3] * px + r[4] * py + r[5] * pz,
                pose.position()[2] + r[6] * px + r[7] * py + r[8] * pz
        };
    }

    /** 行主序旋转矩阵（与 {@code VoidFitVoxelizer#forEachProjectedBlock} 同一四元数展开）。 */
    private static double[] rotation(double[] quaternion) {
        double qx = quaternion[0];
        double qy = quaternion[1];
        double qz = quaternion[2];
        double qw = quaternion[3];
        double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        qx /= norm;
        qy /= norm;
        qz /= norm;
        qw /= norm;
        return new double[] {
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy)
        };
    }

    private static Pose pose(double px, double py, double pz,
            double qx, double qy, double qz, double qw,
            double sx, double sy, double sz) {
        return pose(px, py, pz, qx, qy, qz, qw, sx, sy, sz, 0.0D, 0.0D, 0.0D);
    }

    private static Pose pose(double px, double py, double pz,
            double qx, double qy, double qz, double qw,
            double sx, double sy, double sz,
            double rpx, double rpy, double rpz) {
        return new Pose(new double[] {px, py, pz}, new double[] {qx, qy, qz, qw},
                new double[] {sx, sy, sz}, new double[] {rpx, rpy, rpz});
    }
}
