package dimblend.blocks.treasure;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 贝壳标记方块：埋藏的宝藏生成时由 dimblend-experience 替换宝箱上方沙滩表层的
 * 沙块，摆成 X 标记（替换式、与地面齐平，见 experience 侧 TreasureShellMarker）。
 *
 * <p>属性对齐沙层观感：沙色地图色、近似沙岩硬度、自身掉落（玩家挖走可挪作他用）。
 * 无外部依赖，常驻注册。</p>
 */
public final class ShellMarker {

    public static final DeferredBlock<Block> SHELL_MARKER =
            DimBlendBlocks.BLOCKS.register("shell_marker",
                    () -> new Block(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.SAND)
                            .strength(0.8F)
                            .sound(SoundType.STONE)));

    public static final DeferredItem<BlockItem> SHELL_MARKER_ITEM =
            DimBlendBlocks.ITEMS.register("shell_marker",
                    () -> new BlockItem(SHELL_MARKER.get(), new Item.Properties()));

    /** 触发类加载（BLOCKS/ITEMS 为主类静态字段，注册发生在类初始化时）。 */
    public static void init() {
    }

    private ShellMarker() {
    }
}
