package dimblend.blocks.compat.enderstorage;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import com.simibubi.create.api.registry.CreateRegistries;

import dimblend.blocks.DimBlendBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Create 在场时的附加注册：使列车末影物流箱在 Sable 列车内的 Create 动态装置（滑轨/轴承/便携接口等）
 * 上同样保留频率、朝向与挂载存取能力。仅在 {@code enderstorage} 与 {@code create} 双在场时加载。
 */
public final class TrainEnderChestCreateCompat {

    private static final DeferredRegister<MountedItemStorageType<?>> MOUNTED_STORAGE_TYPES =
            DeferredRegister.create(CreateRegistries.MOUNTED_ITEM_STORAGE_TYPE, DimBlendBlocks.MODID);

    public static final DeferredHolder<MountedItemStorageType<?>, TrainEnderChestMountedStorageType>
            TRAIN_ENDER_CHEST_MOUNTED_STORAGE = MOUNTED_STORAGE_TYPES.register(
                    "train_ender_chest", TrainEnderChestMountedStorageType::new);

    public static void register(IEventBus modEventBus) {
        MOUNTED_STORAGE_TYPES.register(modEventBus);
        modEventBus.addListener(TrainEnderChestCreateCompat::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> MountedItemStorageType.REGISTRY.register(
                TrainEnderChests.TRAIN_ENDER_CHEST.get(),
                TRAIN_ENDER_CHEST_MOUNTED_STORAGE.get()));
    }

    private TrainEnderChestCreateCompat() {
    }
}
