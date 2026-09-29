package dimblend.blocks;

import dimblend.blocks.compat.copycats.CreativeCopycats;
import dimblend.blocks.compat.create.CreativeKinetics;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * 全模组创造物品栏登记：C 板块伪装方块（copycats 守卫）与 K 板块传动件（create 守卫）
 * 两个板块守卫独立——copycats 缺席时传动件仍应可用。各板块 holder 未注册时 get()
 * 会抛异常，守卫必须先行；依赖类只出现在对应守卫之后的方法体内（JVM 惰性解析）。
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class CreativeTabContents {

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.BUILDING_BLOCKS) {
            return;
        }
        if (ModList.get().isLoaded("copycats")) {
            event.accept(CreativeCopycats.CREATIVE_COPYCAT_SLAB_ITEM);
            event.accept(CreativeCopycats.CREATIVE_COPYCAT_BEAM_ITEM);
            event.accept(CreativeCopycats.CREATIVE_COPYCAT_PANEL_ITEM);
            event.accept(CreativeCopycats.CREATIVE_CLEAN_COPYCAT_SLAB_ITEM);
            event.accept(CreativeCopycats.CREATIVE_CLEAN_COPYCAT_PANEL_ITEM);
        }
        if (ModList.get().isLoaded("create")) {
            event.accept(CreativeKinetics.CREATIVE_SHAFT_ITEM);
            event.accept(CreativeKinetics.CREATIVE_COGWHEEL_ITEM);
        }
    }

    private CreativeTabContents() {
    }
}
