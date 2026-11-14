package dimblend.blocks.compat.enderstorage;

import java.util.function.Supplier;

import codechicken.enderstorage.manager.EnderStorageManager;
import dimblend.blocks.DimBlendBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 列车末影物流箱（{@code dimblend_blocks:train_ender_chest}）总注册入口。
 *
 * <p>仅在 {@code enderstorage} 在场时由 {@link DimBlendBlocks} 触达并注册。</p>
 */
public final class TrainEnderChests {

    /**
     * 是否使用独立的列车物流通道（{@code dimblend_train_item}），与地面 {@code enderstorage:ender_chest}
     * 及手持 {@code enderstorage:ender_pouch}（{@code item} 通道）隔离。
     */
    public static final boolean ISOLATED_TRAIN_CHANNEL = true;

    /**
     * 创造模式玩家是否同样受「仅能放置于 Sable 上」限制。
     */
    public static final boolean STRICT_SABLE_IN_CREATIVE = true;

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlendBlocks.MODID);

    private static BlockBehaviour.Properties chestProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .sound(SoundType.NETHERITE_BLOCK)
                .strength(20.0f, 100.0f);
    }

    public static final DeferredBlock<BlockTrainEnderChest> TRAIN_ENDER_CHEST =
            DimBlendBlocks.BLOCKS.register("train_ender_chest", () -> new BlockTrainEnderChest(chestProps()));

    public static final DeferredItem<TrainEnderChestItem> TRAIN_ENDER_CHEST_ITEM =
            DimBlendBlocks.ITEMS.register("train_ender_chest",
                    () -> new TrainEnderChestItem(TRAIN_ENDER_CHEST.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrainEnderChestBlockEntity>>
            TRAIN_ENDER_CHEST_BE = BLOCK_ENTITIES.register("train_ender_chest", trainChestBeType());

    private static Supplier<BlockEntityType<TrainEnderChestBlockEntity>> trainChestBeType() {
        return () -> BlockEntityType.Builder.of(
                TrainEnderChestBlockEntity::new, TRAIN_ENDER_CHEST.get()).build(null);
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
        EnderStorageManager.registerPlugin(new TrainEnderItemStoragePlugin());
        modEventBus.addListener(TrainEnderChests::onRegisterCapabilities);
        NeoForge.EVENT_BUS.addListener(TrainEnderChestGuard::onEntityPlace);
        NeoForge.EVENT_BUS.addListener(TrainEnderChestGuard::onServerTickPost);
        if (ModList.get().isLoaded("create")) {
            TrainEnderChestCreateCompat.register(modEventBus);
        }
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                TRAIN_ENDER_CHEST_BE.get(),
                (be, context) -> be.getItemHandler());
    }

    private TrainEnderChests() {
    }
}
