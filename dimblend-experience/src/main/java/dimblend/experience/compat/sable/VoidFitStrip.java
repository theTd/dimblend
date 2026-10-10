package dimblend.experience.compat.sable;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 保存剥离：区块序列化前把拟合放置的 structure_void 临时剥成空气，序列化完成后还原。
 * 效果等价于"空位从不落盘"，且不做任何 NBT bit 手术。
 *
 * <p>直写 {@link LevelChunkSection#setBlockState}（绕开 {@code LevelChunk} 包装）的前提：
 * structure_void 不遮光（不触发光照重算）、无方块实体。注意 WORLD_SURFACE 系 heightmap
 * 的谓词是"非空气"（{@code Heightmap.NOT_AIR}，Heightmap.java:25/140），放置时空位已计入
 * heightmap，strip 直写不回写 → 落盘的 Heightmaps 与方块数据有一格偏差；heightmap 的
 * 实际消费者几乎都走 MOTION_BLOCKING 系（空位 blocksMotion=false 不计入），影响可忽略，
 * 且区块内任何后续方块变动都会触发重算收敛。</p>
 *
 * <p>本类不引用 Sable 类型，供 mixin（无门控）与 GameTest 直接调用。</p>
 */
public final class VoidFitStrip {

    /**
     * 把本区块内 tracker 记录的、当前确为 structure_void 的格子剥成空气。
     *
     * @return 实际被剥掉的格（供 {@link #restoreFittedVoids} 还原）；未剥任何格时返回空表
     */
    public static LongList stripFittedVoids(ServerLevel level, ChunkAccess chunk) {
        if (!VoidFitTracker.hasAny()) {
            return LongList.of();
        }
        VoidFitTracker tracker = VoidFitTracker.peek(level.dimension());
        if (tracker == null) {
            return LongList.of();
        }
        ChunkPos chunkPos = chunk.getPos();
        LongSet cells = tracker.targetCellsOfChunk(chunkPos);
        if (cells == null || cells.isEmpty()) {
            return LongList.of();
        }
        LongArrayList stripped = new LongArrayList();
        LevelChunkSection[] sections = chunk.getSections();
        LongIterator it = cells.iterator();
        while (it.hasNext()) {
            long cell = it.nextLong();
            int x = BlockPos.getX(cell);
            int y = BlockPos.getY(cell);
            int z = BlockPos.getZ(cell);
            int index = level.getSectionIndex(y);
            if (index < 0 || index >= sections.length) {
                continue;
            }
            LevelChunkSection section = sections[index];
            if (section.getBlockState(x & 15, y & 15, z & 15).is(Blocks.STRUCTURE_VOID)) {
                section.setBlockState(x & 15, y & 15, z & 15, Blocks.AIR.defaultBlockState());
                stripped.add(cell);
            }
        }
        return stripped;
    }

    /** 还原 {@link #stripFittedVoids} 剥掉的格（原样写回 structure_void）。 */
    public static void restoreFittedVoids(ServerLevel level, ChunkAccess chunk, LongList stripped) {
        if (stripped.isEmpty()) {
            return;
        }
        BlockState voidState = Blocks.STRUCTURE_VOID.defaultBlockState();
        LevelChunkSection[] sections = chunk.getSections();
        LongIterator it = stripped.iterator();
        while (it.hasNext()) {
            long cell = it.nextLong();
            int y = BlockPos.getY(cell);
            int index = level.getSectionIndex(y);
            if (index < 0 || index >= sections.length) {
                continue;
            }
            sections[index].setBlockState(BlockPos.getX(cell) & 15, y & 15, BlockPos.getZ(cell) & 15,
                    voidState);
        }
    }

    private VoidFitStrip() {
    }
}
