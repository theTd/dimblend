package dimblend.experience.chassis;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * {@link ChassisRegistry} 的 SavedData 外壳；只持久化身份/蓝图/标称高度/生命周期。
 *
 * <p>这里没有任何 Sable body、joint 或高度约束；读档不会自动锁车，正式锁仍等实机门。</p>
 */
public final class ChassisRegistryData extends SavedData {
    private static final Factory<ChassisRegistryData> FACTORY = new Factory<>(
            ChassisRegistryData::new, (tag, registries) -> load(tag), null);
    private static final String DATA_NAME = "dimblend_chassis_registry";

    private final ChassisRegistry registry = new ChassisRegistry();

    public static ChassisRegistryData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public ChassisRegistry registry() {
        return registry;
    }

    static ChassisRegistryData load(CompoundTag tag) {
        ChassisRegistryData data = new ChassisRegistryData();
        data.registry.read(tag);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return registry.save(tag);
    }
}
