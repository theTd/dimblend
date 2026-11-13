package dimblend.experience.compat.cdg;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 按维度持久化记录安装本功能后新生成的区块坐标（{@code ChunkEvent.Load#isNewChunk()}），
 * 确保新油田削减规则只作用于新生成的区域，既有存档已生成的区块（即便从未被抽油机写入
 * {@code cdg_oil_chunks.dat}）仍保留原有的储量与无限油田状态。
 */
public final class CdgNewOilChunksSavedData extends SavedData {

    private static final String DATA_NAME = "dimblend_experience_cdg_new_oil_chunks";
    private static final String TAG_CHUNKS = "NewChunks";

    private final LongOpenHashSet newChunks = new LongOpenHashSet();

    public CdgNewOilChunksSavedData() {
    }

    private static CdgNewOilChunksSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        CdgNewOilChunksSavedData data = new CdgNewOilChunksSavedData();
        for (long packed : tag.getLongArray(TAG_CHUNKS)) {
            data.newChunks.add(packed);
        }
        return data;
    }

    public static CdgNewOilChunksSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(CdgNewOilChunksSavedData::new, CdgNewOilChunksSavedData::load),
                DATA_NAME);
    }

    public static boolean isNewChunk(ServerLevel level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return false;
        }
        CdgNewOilChunksSavedData data = level.getDataStorage().get(
                new Factory<>(CdgNewOilChunksSavedData::new, CdgNewOilChunksSavedData::load),
                DATA_NAME);
        return data != null && data.contains(chunkPos.toLong());
    }

    public static void markNewChunk(ServerLevel level, ChunkPos chunkPos) {
        if (level == null || chunkPos == null) {
            return;
        }
        get(level).add(chunkPos.toLong());
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CdgNewOilChunksSavedData::onChunkLoad);
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            markNewChunk(serverLevel, event.getChunk().getPos());
        }
    }

    private synchronized boolean contains(long packedChunkPos) {
        return this.newChunks.contains(packedChunkPos);
    }

    private synchronized void add(long packedChunkPos) {
        if (this.newChunks.add(packedChunkPos)) {
            this.setDirty();
        }
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(TAG_CHUNKS, this.newChunks.toLongArray());
        return tag;
    }
}
