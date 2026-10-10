package dimblend.experience.compat.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 把一个 Sable 载具的占据格体素化为世界坐标目标格集合（纯计算，无可变状态）。
 *
 * <p>占据格从 plot 区块直接枚举（跳过全空 section、按 {@code plot.getBoundingBox()} 裁剪），
 * 每格中心经 {@code logicalPose().transformPosition} 投影到世界侧。旋转是任意四元数，
 * 只投中心点会在斜置时漏缝（漏缝=漏水），故按旋转后方块的保守覆盖取格：
 * 半 extent {@code h = |R| · (scale/2)}，覆盖 AABB(w−h, w+h) 触及的所有格
 * （每格最多 8 个世界格，典型 1-2）。</p>
 */
public final class VoidFitVoxelizer {

    /** 单个 plot 占据格的投影结果：plot 内坐标、方块状态、世界侧中心与保守半 extent。 */
    @FunctionalInterface
    public interface ProjectedBlockConsumer {
        void accept(BlockPos plotPos, BlockState state,
                double worldX, double worldY, double worldZ,
                double halfX, double halfY, double halfZ);
    }

    public static LongOpenHashSet voxelize(ServerLevel level, ServerSubLevel subLevel) {
        LongOpenHashSet out = new LongOpenHashSet();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        forEachProjectedBlock(subLevel, (plotPos, state, wx, wy, wz, hx, hy, hz) ->
                addCoveredCells(out, wx, wy, wz, hx, hy, hz, minY, maxY));
        return out;
    }

    /**
     * 枚举载具的全部 plot 占据格并给出世界侧投影（{@link SableLavaMelt} 的接触扫描
     * 与本类的体素化共用同一枚举，旋转保守半 extent 的口径只有这一份）。
     */
    public static void forEachProjectedBlock(ServerSubLevel subLevel, ProjectedBlockConsumer consumer) {
        LevelPlot plot = subLevel.getPlot();
        Pose3dc pose = subLevel.logicalPose();
        BoundingBox3ic bounds = plot.getBoundingBox();

        Quaterniondc q = pose.orientation();
        Vector3dc scale = pose.scale();
        // 旋转矩阵逐分量绝对值（|R_ij|），乘半格 extents，得世界侧保守半 extent
        double ex = 0.5 * scale.x();
        double ey = 0.5 * scale.y();
        double ez = 0.5 * scale.z();
        double qx = q.x(), qy = q.y(), qz = q.z(), qw = q.w();
        double r00 = 1 - 2 * (qy * qy + qz * qz);
        double r01 = 2 * (qx * qy - qz * qw);
        double r02 = 2 * (qx * qz + qy * qw);
        double r10 = 2 * (qx * qy + qz * qw);
        double r11 = 1 - 2 * (qx * qx + qz * qz);
        double r12 = 2 * (qy * qz - qx * qw);
        double r20 = 2 * (qx * qz - qy * qw);
        double r21 = 2 * (qy * qz + qx * qw);
        double r22 = 1 - 2 * (qx * qx + qy * qy);
        double hx = Math.abs(r00) * ex + Math.abs(r01) * ey + Math.abs(r02) * ez;
        double hy = Math.abs(r10) * ex + Math.abs(r11) * ey + Math.abs(r12) * ez;
        double hz = Math.abs(r20) * ex + Math.abs(r21) * ey + Math.abs(r22) * ez;

        Vector3d world = new Vector3d();
        Vector3d center = new Vector3d();
        BlockPos.MutableBlockPos plotPos = new BlockPos.MutableBlockPos();

        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            ChunkPos chunkPos = chunk.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection section = sections[s];
                if (section.hasOnlyAir()) {
                    continue;
                }
                int sectionY = chunk.getSectionYFromSectionIndex(s);
                int baseY = SectionPos.sectionToBlockCoord(sectionY);
                // 按 plot 局部占用 AABB 裁剪本 section 的扫描范围
                int y0 = Math.max(0, bounds.minY() - baseY);
                int y1 = Math.min(15, bounds.maxY() - baseY);
                if (y0 > y1) {
                    continue;
                }
                int x0 = Math.max(0, bounds.minX() - chunkPos.getMinBlockX());
                int x1 = Math.min(15, bounds.maxX() - chunkPos.getMinBlockX());
                int z0 = Math.max(0, bounds.minZ() - chunkPos.getMinBlockZ());
                int z1 = Math.min(15, bounds.maxZ() - chunkPos.getMinBlockZ());
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) {
                                continue;
                            }
                            int px = chunkPos.getMinBlockX() + x;
                            int py = baseY + y;
                            int pz = chunkPos.getMinBlockZ() + z;
                            center.set(px + 0.5, py + 0.5, pz + 0.5);
                            pose.transformPosition(center, world);
                            consumer.accept(plotPos.set(px, py, pz), state,
                                    world.x, world.y, world.z, hx, hy, hz);
                        }
                    }
                }
            }
        }
    }

    /** 把以 (wx,wy,wz) 为中心、半 extent (hx,hy,hz) 的 AABB 触及的所有世界格加入集合。 */
    private static void addCoveredCells(LongOpenHashSet out,
            double wx, double wy, double wz,
            double hx, double hy, double hz, int minY, int maxY) {
        int x0 = (int) Math.floor(wx - hx);
        int x1 = (int) Math.floor(wx + hx);
        int y0 = Math.max(minY, (int) Math.floor(wy - hy));
        int y1 = Math.min(maxY, (int) Math.floor(wy + hy));
        int z0 = (int) Math.floor(wz - hz);
        int z1 = Math.min(maxY, (int) Math.floor(wz + hz));
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    out.add(BlockPos.asLong(x, y, z));
                }
            }
        }
    }

    private VoidFitVoxelizer() {
    }
}
