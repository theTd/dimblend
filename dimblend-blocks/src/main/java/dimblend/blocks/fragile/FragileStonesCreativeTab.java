package dimblend.blocks.fragile;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * 易碎石头家族入建筑方块创造栏（无外部依赖，常驻）。
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class FragileStonesCreativeTab {

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(FragileStones.FRAGILE_COBBLESTONE_ITEM);
            event.accept(FragileStones.FRAGILE_STONE_ITEM);
        }
    }

    private FragileStonesCreativeTab() {
    }
}
