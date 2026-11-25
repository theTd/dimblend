package dimblend.blocks.compat.create.funnel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.foundation.item.ItemHelper;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 卸料漏斗双形态的注册入口（仅 Create 在场时由主类调用）。
 *
 * <p>双形态共用一套方块实体类型：立式 {@link DumpFunnelBlock} + 传送带
 * {@link DumpBeltFunnelBlock}。传送带形态没有独立物品（战利品表与 clone item 都给
 * {@code dimblend_blocks:dump_funnel}）。</p>
 */
public final class DumpFunnels {

    public static final ResourceLocation DUMP_FUNNEL_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "dump_funnel");
    public static final ResourceLocation DUMP_BELT_FUNNEL_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "dump_belt_funnel");

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlendBlocks.MODID);

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(1.5F)
                .sound(SoundType.METAL);
    }

    public static final DeferredBlock<DumpFunnelBlock> DUMP_FUNNEL_BLOCK =
            DimBlendBlocks.BLOCKS.register("dump_funnel", () -> new DumpFunnelBlock(props()));

    public static final DeferredBlock<DumpBeltFunnelBlock> DUMP_BELT_FUNNEL_BLOCK =
            DimBlendBlocks.BLOCKS.register("dump_belt_funnel", () -> new DumpBeltFunnelBlock(props()));

    public static final DeferredItem<DumpFunnelItem> DUMP_FUNNEL_ITEM =
            DimBlendBlocks.ITEMS.register("dump_funnel",
                    () -> new DumpFunnelItem(DUMP_FUNNEL_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DumpFunnelBlockEntity>> DUMP_FUNNEL_BE =
            BLOCK_ENTITIES.register("dump_funnel", dumpFunnelType());

    private static Supplier<BlockEntityType<DumpFunnelBlockEntity>> dumpFunnelType() {
        return () -> BlockEntityType.Builder.of(
                (pos, state) -> new DumpFunnelBlockEntity(DUMP_FUNNEL_BE.get(), pos, state),
                DUMP_FUNNEL_BLOCK.get(), DUMP_BELT_FUNNEL_BLOCK.get()).build(null);
    }

    /** 仅在 Create 已加载时由主类调用。 */
    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
        modEventBus.addListener(DumpFunnels::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        // 方块实例要等注册事件后才存在，装置行为登记放 common setup 的 enqueueWork
        event.enqueueWork(() -> {
            DumpFunnelMovementBehaviour movement = new DumpFunnelMovementBehaviour();
            MovementBehaviour.REGISTRY.register(DUMP_FUNNEL_BLOCK.get(), movement);
            MovementBehaviour.REGISTRY.register(DUMP_BELT_FUNNEL_BLOCK.get(), movement);
        });
    }

    /** 漏斗朝向（含 {@code null} 保护，装置里可能碰到非漏斗状态）。 */
    public static net.minecraft.core.Direction funnelFacing(BlockState state) {
        net.minecraft.core.Direction facing = AbstractFunnelBlock.getFunnelFacing(state);
        return facing == null ? net.minecraft.core.Direction.DOWN : facing;
    }

    /**
     * 装置内卸料：按 {@code Item + DataComponents} 汇总装置内置物，只抽"总数严格大于阈值"
     * 的物品 1 个；过滤槽为空返回空。
     */
    public static ItemStack extractFromContraption(MovementContext context, int amount) {
        FilterItemStack filter = context.getFilterFromBE();
        if (filter == null || filter.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int stacks = DumpFunnelRules.clampStacks(context.blockEntityData.getInt("FilterAmount"));
        IItemHandler items = context.contraption.getStorage().getAllItems();
        List<DumpFunnelRules.ItemCount> totals = DumpFunnelRules.aggregate(stacksOf(items));
        return ItemHelper.extract(items, stack -> {
            if (stack.isEmpty() || !filter.test(context.world, stack)) {
                return false;
            }
            return DumpFunnelRules.shouldDump(DumpFunnelRules.totalOf(totals, stack), stacks,
                    stack.getMaxStackSize());
        }, ItemHelper.ExtractionCountMode.EXACTLY, amount, false);
    }

    /** 只读遍历装置内置物（不抽、不改）。 */
    private static List<ItemStack> stacksOf(IItemHandler items) {
        List<ItemStack> stacks = new ArrayList<>(Math.max(0, items.getSlots()));
        for (int slot = 0; slot < items.getSlots(); slot++) {
            ItemStack stack = items.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    private DumpFunnels() {
    }
}
