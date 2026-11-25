package dimblend.blocks.compat.create;

import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;

import dimblend.blocks.DimBlendBlocks;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * 思索（Ponder）插件：只登记"标签归类"，不注册任何场景。
 *
 * <p>卸料漏斗归 {@link AllCreatePonderTags#LOGISTICS}（与漏斗/传送带一族同栏），
 * 卸料仪表归 {@link AllCreatePonderTags#HIGH_LOGISTICS}（仅 Deployer 在场时才有意义）。
 * 用标签而不是场景，是因为这两件物品的玩法一句话就能讲清（见物品 tooltip），
 * 强行配场景反而占位。</p>
 */
public class DimBlendBlocksPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return DimBlendBlocks.MODID;
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.addTagToComponent(dimblend.blocks.compat.create.funnel.DumpFunnels.DUMP_FUNNEL_ID,
                AllCreatePonderTags.LOGISTICS);
        if (ModList.get().isLoaded("deployer")) {
            helper.addTagToComponent(dimblend.blocks.compat.deployer.DumpGauges.DUMP_GAUGE_ID,
                    AllCreatePonderTags.HIGH_LOGISTICS);
        }
    }
}
