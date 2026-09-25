package dimblend.blocks.fragile;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 易碎石头家族：岩浆遇水生成的圆石/石头新变体。
 *
 * <p>行为：徒手可快速挖掉（destroyTime 0.5），无战利品表（挖掘不掉落任何物品），
 * 爆抗 3.0（约为原版石头一半）。源岩浆遇水仍走原版黑曜石，不动。</p>
 *
 * <p>生成重定向见 {@link FragileFluidResults}（监听
 * {@code BlockEvent.FluidPlaceBlockEvent}，同时覆盖注册表圆石路径与
 * {@code LavaFluid} 下流石头路径）。无外部依赖，常驻注册。</p>
 */
public final class FragileStones {

    private static BlockBehaviour.Properties fragileProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .instrument(NoteBlockInstrument.BASEDRUM)
                .strength(0.5F, 3.0F)
                .sound(SoundType.STONE)
                .noLootTable();
    }

    public static final DeferredBlock<Block> FRAGILE_COBBLESTONE =
            DimBlendBlocks.BLOCKS.register("fragile_cobblestone",
                    () -> new Block(fragileProps()));

    public static final DeferredBlock<Block> FRAGILE_STONE =
            DimBlendBlocks.BLOCKS.register("fragile_stone",
                    () -> new Block(fragileProps()));

    public static final DeferredItem<BlockItem> FRAGILE_COBBLESTONE_ITEM =
            DimBlendBlocks.ITEMS.register("fragile_cobblestone",
                    () -> new BlockItem(FRAGILE_COBBLESTONE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> FRAGILE_STONE_ITEM =
            DimBlendBlocks.ITEMS.register("fragile_stone",
                    () -> new BlockItem(FRAGILE_STONE.get(), new Item.Properties()));

    /** 触发类加载（BLOCKS/ITEMS 为主类静态字段，注册发生在类初始化时）。 */
    public static void init() {
    }

    private FragileStones() {
    }
}
