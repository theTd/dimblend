package dimblend.carwash.chassis;

import com.copycatsplus.copycats.content.copycat.slab.CopycatSlabBlock;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.decoration.copycat.CopycatPanelBlock;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * 车架方块判定。车架 = dimblend-blocks 提供的
 * <ul>
 * <li>创造模式伪装板，倒置（{@code FACING=DOWN}，贴在格子顶部 3px）</li>
 * <li>创造模式伪装半砖，倒置（竖轴上半砖）或双层</li>
 * </ul>
 * 跨模组只按注册名引用（{@link DeferredHolder}），不 import dimblend-blocks 的实现类；
 * 属性常量取自其上游基类（Create 伪装板 / Copycats+ 伪装半砖）。
 */
public final class ChassisBlocks {

    private static final String BLOCKS_MODID = "dimblend_blocks";

    public static final DeferredHolder<Block, Block> CREATIVE_PANEL = block("creative_copycat_panel");
    public static final DeferredHolder<Block, Block> CREATIVE_SLAB = block("creative_copycat_slab");

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CopycatBlockEntity>> CREATIVE_PANEL_BLOCK_ENTITY =
            DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, blocksId("creative_create_copycat"));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MultiStateCopycatBlockEntity>> CREATIVE_SLAB_BLOCK_ENTITY =
            DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, blocksId("creative_multi_state_copycat"));

    /** 该状态当前是否算车架（形态会随放置/叠砖变化，每次按状态判）。 */
    public static boolean isChassis(BlockState state) {
        if (isBlock(state, CREATIVE_PANEL)) {
            return state.getValue(CopycatPanelBlock.FACING) == Direction.DOWN;
        }
        if (isBlock(state, CREATIVE_SLAB)) {
            SlabType type = state.getValue(CopycatSlabBlock.SLAB_TYPE);
            return type == SlabType.DOUBLE
                    || (type == SlabType.TOP && state.getValue(CopycatSlabBlock.AXIS) == Direction.Axis.Y);
        }
        return false;
    }

    /** 可能成为车架的方块（已注册的那些），供客户端包装模型。 */
    public static List<Block> candidateBlocks() {
        List<Block> blocks = new ArrayList<>(2);
        if (CREATIVE_PANEL.isBound()) {
            blocks.add(CREATIVE_PANEL.get());
        }
        if (CREATIVE_SLAB.isBound()) {
            blocks.add(CREATIVE_SLAB.get());
        }
        return blocks;
    }

    private static boolean isBlock(BlockState state, DeferredHolder<Block, Block> holder) {
        return holder.isBound() && state.is(holder.get());
    }

    private static DeferredHolder<Block, Block> block(String path) {
        return DeferredHolder.create(Registries.BLOCK, blocksId(path));
    }

    private static ResourceLocation blocksId(String path) {
        return ResourceLocation.fromNamespaceAndPath(BLOCKS_MODID, path);
    }

    private ChassisBlocks() {
    }
}
