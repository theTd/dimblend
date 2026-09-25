package dimblend.experience.nickname;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * A8 物品昵称仓库：item id → 昵称，存世界 SavedData（overworld 数据存储），
 * 对本存档全局生效。纯显示层数据，不触碰物品 NBT。
 */
public class NicknameStore extends SavedData {

    private static final String DATA_NAME = "dimblend_experience_nicknames";
    public static final int MAX_LENGTH = 64;

    private final Map<ResourceLocation, String> nicknames = new HashMap<>();

    public static NicknameStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<NicknameStore>(NicknameStore::new, NicknameStore::load),
                DATA_NAME);
    }

    /** 设昵称并全服同步；返回是否发生变化。 */
    public boolean set(MinecraftServer server, ResourceLocation itemId, String nickname) {
        boolean changed = !nickname.equals(nicknames.get(itemId));
        if (changed) {
            nicknames.put(itemId, nickname);
            setDirty();
            NicknameSyncPayload.broadcast(server);
        }
        return changed;
    }

    /** 清除昵称并全服同步；返回是否发生变化。 */
    public boolean clear(MinecraftServer server, ResourceLocation itemId) {
        boolean changed = nicknames.remove(itemId) != null;
        if (changed) {
            setDirty();
            NicknameSyncPayload.broadcast(server);
        }
        return changed;
    }

    public Map<ResourceLocation, String> view() {
        return Map.copyOf(nicknames);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<ResourceLocation, String> entry : nicknames.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putString("id", entry.getKey().toString());
            item.putString("name", entry.getValue());
            list.add(item);
        }
        tag.put("nicknames", list);
        return tag;
    }

    private static NicknameStore load(CompoundTag tag, HolderLookup.Provider registries) {
        NicknameStore store = new NicknameStore();
        ListTag list = tag.getList("nicknames", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(item.getString("id"));
            String name = item.getString("name");
            if (id != null && !name.isEmpty()) {
                store.nicknames.put(id, name);
            }
        }
        return store;
    }
}
