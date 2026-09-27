package dimblend.purge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Last known chunk X of every player who has been in the rotating dimension, saved with that
 * dimension ({@code data/dimblend_purge_anchors.dat}). Region purge keeps a window around each
 * entry, so a player who logs out, dies elsewhere or walks through a portal finds their spot
 * intact when they come back, across server restarts. Entries are never dropped: a player who
 * never returns costs one keep window of disk. Server thread only.
 */
public final class RegionPurgeAnchors extends SavedData {
    private static final String FILE_ID = "dimblend_purge_anchors";
    private static final String ANCHORS_TAG = "anchors";
    private static final String UUID_TAG = "id";
    private static final String CHUNK_X_TAG = "chunkX";
    private static final SavedData.Factory<RegionPurgeAnchors> FACTORY =
            new SavedData.Factory<>(RegionPurgeAnchors::new, RegionPurgeAnchors::load, null);

    private final Map<UUID, Integer> chunkXByPlayer = new HashMap<>();

    public static RegionPurgeAnchors get(ServerLevel rotating) {
        return rotating.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public void record(UUID player, int chunkX) {
        Integer previous = this.chunkXByPlayer.put(player, chunkX);
        if (previous == null || previous != chunkX) {
            this.setDirty();
        }
    }

    public int size() {
        return this.chunkXByPlayer.size();
    }

    public int[] chunkXs() {
        int[] result = new int[this.chunkXByPlayer.size()];
        int i = 0;
        for (int chunkX : this.chunkXByPlayer.values()) {
            result[i++] = chunkX;
        }
        return result;
    }

    private static RegionPurgeAnchors load(CompoundTag tag, HolderLookup.Provider registries) {
        RegionPurgeAnchors anchors = new RegionPurgeAnchors();
        ListTag list = tag.getList(ANCHORS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID(UUID_TAG)) {
                anchors.chunkXByPlayer.put(entry.getUUID(UUID_TAG), entry.getInt(CHUNK_X_TAG));
            }
        }
        return anchors;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Integer> anchor : this.chunkXByPlayer.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(UUID_TAG, anchor.getKey());
            entry.putInt(CHUNK_X_TAG, anchor.getValue());
            list.add(entry);
        }
        tag.put(ANCHORS_TAG, list);
        return tag;
    }
}
