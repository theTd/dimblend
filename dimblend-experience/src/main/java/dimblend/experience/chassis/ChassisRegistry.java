package dimblend.experience.chassis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 标准车架身份登记（纯数据）：UUID、蓝图、校准标称高度、生命周期。
 *
 * <p><b>身份与 constraint 分离。</b>这里不保存 joint、不启动物理高度约束、不改质量/
 * 驱动/应力/制动。脱轨或卸载只是改 {@link Lifecycle}，身份保留；只有真拆解才
 * {@link #remove(UUID)}。</p>
 *
 * <p><b>标称高度必须显式给。</b>API 没有“当前位置”入参，因此不可能从已下沉的 pos 采样；
 * 调用方应传蓝图地板基准换算出的正常轨高（例如测试车 66.0）。校准
 * {@link #calibrate(UUID, double)} 也只改这个数值，不读世界。</p>
 *
 * <p><b>持久化。</b>{@link #save(CompoundTag)} / {@link #load(CompoundTag)} 是纯 NBT
 * 往返，{@link ChassisRegistryData} 再把它挂到 SavedData；单测不需要 ServerLevel。</p>
 */
public final class ChassisRegistry {
    public static final double MIN_NOMINAL_HEIGHT = -64.0;
    public static final double MAX_NOMINAL_HEIGHT = 320.0;

    public enum Lifecycle {
        /** Registered but not yet stable/locked; no constraint exists. */
        REGISTERED,
        /** Stable identity; a future lock may attach, still not implied here. */
        ACTIVE,
        /** Derailed: identity and nominal height are kept. */
        DERAILED,
        /** Chunk/sub-level unloaded: identity and nominal height are kept. */
        UNLOADED
    }

    public record Entry(UUID id, ChassisBlueprint blueprint, double nominalHeight, Lifecycle lifecycle) {
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public boolean register(UUID id, ChassisBlueprint blueprint, double nominalHeight) {
        if (id == null || blueprint == null || !validNominalHeight(nominalHeight)) {
            return false;
        }
        entries.put(id, new Entry(id, blueprint, nominalHeight, Lifecycle.REGISTERED));
        return true;
    }

    /** Explicit calibration; never reads a current/sunken position. */
    public boolean calibrate(UUID id, double nominalHeight) {
        Entry current = entries.get(id);
        if (current == null || !validNominalHeight(nominalHeight)) {
            return false;
        }
        entries.put(id, new Entry(id, current.blueprint(), nominalHeight, current.lifecycle()));
        return true;
    }

    public boolean mark(UUID id, Lifecycle lifecycle) {
        Entry current = entries.get(id);
        if (current == null || lifecycle == null) {
            return false;
        }
        entries.put(id, new Entry(id, current.blueprint(), current.nominalHeight(), lifecycle));
        return true;
    }

    /** Only a real disassembly may delete the identity. */
    public boolean remove(UUID id) {
        return id != null && entries.remove(id) != null;
    }

    public Entry get(UUID id) {
        return id == null ? null : entries.get(id);
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public int size() {
        return entries.size();
    }

    public static boolean validNominalHeight(double nominalHeight) {
        return Double.isFinite(nominalHeight)
                && nominalHeight >= MIN_NOMINAL_HEIGHT && nominalHeight <= MAX_NOMINAL_HEIGHT;
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag item = new CompoundTag();
            item.putString("id", entry.id().toString());
            item.putString("blueprint", entry.blueprint().name());
            item.putDouble("nominalHeight", entry.nominalHeight());
            item.putString("lifecycle", entry.lifecycle().name());
            list.add(item);
        }
        tag.put("entries", list);
        return tag;
    }

    public void read(CompoundTag tag) {
        entries.clear();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag item = list.getCompound(index);
            if (!item.contains("nominalHeight", Tag.TAG_DOUBLE)) {
                continue;
            }
            try {
                UUID id = UUID.fromString(item.getString("id"));
                ChassisBlueprint blueprint = ChassisBlueprint.valueOf(item.getString("blueprint"));
                double nominalHeight = item.getDouble("nominalHeight");
                Lifecycle lifecycle = Lifecycle.valueOf(item.getString("lifecycle"));
                if (validNominalHeight(nominalHeight)) {
                    entries.put(id, new Entry(id, blueprint, nominalHeight, lifecycle));
                }
            } catch (IllegalArgumentException ignored) {
                // A corrupt/removed entry must not poison the rest of the registry.
            }
        }
    }

    public static ChassisRegistry load(CompoundTag tag) {
        ChassisRegistry registry = new ChassisRegistry();
        registry.read(tag);
        return registry;
    }
}
