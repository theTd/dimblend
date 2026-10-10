package dimblend.blocks.treasure;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * 贝壳标记入建筑方块创造栏（无外部依赖，常驻）。
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class ShellMarkerCreativeTab {

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(ShellMarker.SHELL_MARKER_ITEM);
        }
    }

    private ShellMarkerCreativeTab() {
    }
}
