package dimblend.blocks.compat.copycats;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * C 板块物品入创造物品栏：仅 Copycats+ 在场时注册条目
 * （holder 未注册时 get() 会抛异常，守卫必须先行）。
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class CreativeCopycatCreativeTab {

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (!ModList.get().isLoaded("copycats")) {
            return;
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(CreativeCopycats.CREATIVE_COPYCAT_SLAB_ITEM);
            event.accept(CreativeCopycats.CREATIVE_COPYCAT_BEAM_ITEM);
            event.accept(CreativeCopycats.CREATIVE_COPYCAT_PANEL_ITEM);
        }
    }

    private CreativeCopycatCreativeTab() {
    }
}
