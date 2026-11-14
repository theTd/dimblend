package dimblend.experience.client.ponder;

import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import dimblend.experience.DimBlend;
import net.createmod.ponder.api.registration.MultiTagBuilder;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * 显示连接器思索的"数据来源"条目：把保险库类方块补进 {@link AllCreatePonderTags#DISPLAY_SOURCES}，
 * 并把只在 Create + Simurail 时才有内容的条目改成按各自 mod 在场性判断。
 *
 * <p>本插件只在客户端、且 Create 在场时注册（见 {@code DimBlendClient}）。条目一律写成物品/方块
 * 注册名：Ponder 的 {@code PonderTagScreen} 用 {@code RegisteredObjectsHelper#getItemOrBlock} 解析，
 * 解析不到就静默过滤，所以缺席的 addon 既不会崩也不会留空图标。</p>
 */
public final class SupplementPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return DimBlend.MODID;
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        MultiTagBuilder.Tag<ResourceLocation> sources = helper.addToTag(AllCreatePonderTags.DISPLAY_SOURCES);
        // 物品保险库：本模组给它补上了"读取存量-计位/计件"来源
        sources.add(ResourceLocation.fromNamespaceAndPath("create", "item_vault"));
        if (ModList.get().isLoaded("create_connected")) {
            // 纵向物品保险库
            sources.add(ResourceLocation.fromNamespaceAndPath("create_connected", "item_silo"));
        }
        if (ModList.get().isLoaded("dndecor")) {
            // 集装箱（未染色那一种；染色变种由同一条目代表）
            sources.add(ResourceLocation.fromNamespaceAndPath("dndecor", "container"));
        }
        if (ModList.get().isLoaded("simurail")) {
            sources.add(ResourceLocation.fromNamespaceAndPath("simurail", "physics_bogey"));
        }
    }
}
