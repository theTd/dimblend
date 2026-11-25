package dimblend.blocks;

import dimblend.blocks.compat.copycats.CreativeCopycats;
import dimblend.blocks.compat.create.CreativeKinetics;
import dimblend.blocks.compat.enderstorage.TrainEnderChests;
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
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            // 热冰：无外部依赖，常驻登记
            event.accept(HotIce.HOT_ICE);
            return;
        }
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
            // 卸料漏斗：Create 漏斗的卸料变体（双形态共用一个物品）
            event.accept(dimblend.blocks.compat.create.funnel.DumpFunnels.DUMP_FUNNEL_ITEM);
        }
        if (ModList.get().isLoaded("create") && ModList.get().isLoaded("deployer")) {
            // 卸料仪表：面板物品，走 Deployer 的自定义 PanelType
            event.accept(dimblend.blocks.compat.deployer.DumpGauges.DUMP_GAUGE_ITEM);
        }
        if (ModList.get().isLoaded("enderstorage")) {
            event.accept(TrainEnderChests.TRAIN_ENDER_CHEST_ITEM);
        }
        if (ModList.get().isLoaded("create") && ModList.get().isLoaded("sable")) {
            // 列车加压管道：依赖 Create 管道渲染基类 + Sable 子层级判定
            event.accept(dimblend.blocks.compat.rail.TrainTubes.TUBE_ITEM);
        }
    }

    private CreativeTabContents() {
    }
}
