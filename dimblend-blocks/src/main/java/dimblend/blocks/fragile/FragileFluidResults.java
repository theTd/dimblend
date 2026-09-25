package dimblend.blocks.fragile;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 岩浆遇水生成重定向：把原版圆石/石头结果换成易碎变体。
 *
 * <p>官方收敛口径（NeoForge 21.1.249 实测）：{@code FluidInteractionRegistry}
 * （流动岩浆遇水→圆石/黑曜石）与补丁后的 {@code LavaFluid#spreadTo}
 * （岩浆下流遇水→石头）两条路径都会触发 {@code FluidPlaceBlockEvent}，
 * 且事件在 setBlock 之前触发、可改结果。直接注入
 * {@code LiquidBlock#shouldSpreadLiquid} 不可行——NeoForge 已将其调用点
 * 改为注册表，该方法是生产死代码。</p>
 *
 * <p>判据：结果是圆石且源头是岩浆→易碎圆石；结果是石头且目标格是水→易碎石头。
 * 黑曜石/玄武岩/点火路径原样放行。</p>
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class FragileFluidResults {

    @SubscribeEvent
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        BlockState result = event.getNewState();
        if (result.is(Blocks.COBBLESTONE)
                && event.getLevel().getFluidState(event.getLiquidPos()).is(FluidTags.LAVA)) {
            event.setNewState(FragileStones.FRAGILE_COBBLESTONE.get().defaultBlockState());
        } else if (result.is(Blocks.STONE)
                && event.getLevel().getFluidState(event.getPos()).is(FluidTags.WATER)) {
            event.setNewState(FragileStones.FRAGILE_STONE.get().defaultBlockState());
        }
    }

    private FragileFluidResults() {
    }
}
