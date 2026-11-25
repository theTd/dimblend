package dimblend.blocks.compat.rail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 列车加压管道的维度级存档：跨车厢连接表 + 各分量共用的 100 mB 液池。
 *
 * <p>液池按「分量的规范锚点」存取（{@link TrainTubeNetworkManager} 用分量内字典序最小的节点键当锚点），
 * 于是跨车厢的另一段读到的是同一个池——两个车厢的管道各自 BFS，连接表把它们归到同一分量。</p>
 *
 * <p><b>加载不清池</b>：{@code load} 只做反序列化，不碰任何拓扑逻辑，避免"从磁盘回读即清空"
 * 把已存的液体抹掉。</p>
 */
public final class TrainTubeSavedData extends SavedData {

    public static final String FILE_ID = "dimblend_train_tubes";

    private static final SavedData.Factory<TrainTubeSavedData> FACTORY =
            new SavedData.Factory<>(TrainTubeSavedData::new, TrainTubeSavedData::load, null);

    private final List<TrainTubeLink> links = new ArrayList<>();
    private final Map<String, FluidStack> pools = new LinkedHashMap<>();

    private TrainTubeSavedData() {
    }

    public static TrainTubeSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    // ------------------------------------------------------------------ 连接表

    public List<TrainTubeLink> links() {
        return links;
    }

    /** 该端点是否已经有一条跨车厢连接（不分方向）。 */
    @Nullable
    public TrainTubeLink linkAt(@Nullable UUID subLevel, @Nullable BlockPos pos) {
        if (subLevel == null || pos == null) {
            return null;
        }
        for (TrainTubeLink link : links) {
            if (link.anchoredAt(subLevel, pos)) {
                return link;
            }
        }
        return null;
    }

    public void addLink(TrainTubeLink link) {
        links.add(link);
        setDirty();
    }

    public boolean removeLink(TrainTubeLink link) {
        boolean removed = links.remove(link);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** 拆掉所有锚在给定端点上的连接（端点失效时用）。 */
    public List<TrainTubeLink> removeLinksAt(UUID subLevel, BlockPos pos) {
        List<TrainTubeLink> removed = new ArrayList<>();
        links.removeIf(link -> {
            if (link.anchoredAt(subLevel, pos)) {
                removed.add(link);
                return true;
            }
            return false;
        });
        if (!removed.isEmpty()) {
            setDirty();
        }
        return removed;
    }

    /** 按位置拆连接（子层级已经查不到时的兜底：位置在一个维度里唯一）。 */
    public List<TrainTubeLink> removeLinksAtPos(BlockPos pos) {
        List<TrainTubeLink> removed = new ArrayList<>();
        links.removeIf(link -> {
            if (link.posA().equals(pos) || link.posB().equals(pos)) {
                removed.add(link);
                return true;
            }
            return false;
        });
        if (!removed.isEmpty()) {
            setDirty();
        }
        return removed;
    }

    /** 拆掉所有涉及某个子层级的连接（子层级被移除时用）。 */
    public List<TrainTubeLink> removeLinksTouching(UUID subLevel) {
        List<TrainTubeLink> removed = new ArrayList<>();
        links.removeIf(link -> {
            if (link.touches(subLevel)) {
                removed.add(link);
                return true;
            }
            return false;
        });
        if (!removed.isEmpty()) {
            setDirty();
        }
        return removed;
    }

    // ------------------------------------------------------------------ 液池

    @Nullable
    public FluidStack pool(String key) {
        FluidStack stack = pools.get(key);
        return stack == null || stack.isEmpty() ? null : stack;
    }

    public void putPool(String key, @Nullable FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            if (pools.remove(key) != null) {
                setDirty();
            }
            return;
        }
        pools.put(key, stack.copy());
        setDirty();
    }

    public boolean removePool(String key) {
        boolean removed = pools.remove(key) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public Set<String> poolKeys() {
        return pools.keySet();
    }

    // ------------------------------------------------------------------ 序列化

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag linkList = new ListTag();
        for (TrainTubeLink link : links) {
            linkList.add(link.save());
        }
        tag.put("Links", linkList);

        CompoundTag poolTag = new CompoundTag();
        for (Map.Entry<String, FluidStack> entry : pools.entrySet()) {
            FluidStack stack = entry.getValue();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            poolTag.put(entry.getKey(), stack.save(registries));
        }
        tag.put("Pools", poolTag);
        return tag;
    }

    public static TrainTubeSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainTubeSavedData data = new TrainTubeSavedData();
        ListTag linkList = tag.getList("Links", Tag.TAG_COMPOUND);
        for (int index = 0; index < linkList.size(); index++) {
            data.links.add(TrainTubeLink.load(linkList.getCompound(index)));
        }
        CompoundTag poolTag = tag.getCompound("Pools");
        for (String key : poolTag.getAllKeys()) {
            FluidStack stack = FluidStack.parseOptional(registries, poolTag.getCompound(key));
            if (!stack.isEmpty()) {
                data.pools.put(key, stack.copy());
            }
        }
        return data;
    }
}
