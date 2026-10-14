package dimblend.craft;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import dimblend.craft.recipe.RecipeEditReloadListener;
import dimblend.craft.recipe.RecipeEditApplicator;
import dimblend.craft.recipe.RecipeEditRules;

/**
 * Dimblend Craft：TrainTripWorld 整合包专用的运行时配方调整 mod。
 *
 * <p>本 mod 不注册任何方块/物品，唯一的职责是对整合包内其他 mod 的配方按
 * docs/配方修改需求.md 的规则表做批量修改与删除。规则分两阶段应用：</p>
 *
 * <ul>
 *   <li>数据包重载阶段（初始加载与 /reload）：普通数据包配方，见
 *       {@link RecipeEditRules#edits()}；</li>
 *   <li>服务器启动后阶段（{@link ServerStartedEvent}）：上游在重载之外动态注入的
 *       配方（如哨兵机械臂按 TACZ 弹药表注入的弹药切削配方，注入点为
 *       {@code ServerAboutToStartEvent}，早于本阶段），见 {@link RecipeEditRules#lateEdits()}。</li>
 * </ul>
 */
@Mod(DimblendCraft.MODID)
public class DimblendCraft {
    public static final String MODID = "dimblend_craft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DimblendCraft(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new RecipeEditReloadListener(event));
        LOGGER.info("[配方] 已注册配方编辑重载监听器");
    }

    private void onServerStarted(ServerStartedEvent event) {
        RecipeEditApplicator.apply(event.getServer().getRecipeManager(), event.getServer().registryAccess(),
                RecipeEditRules.lateEdits());
    }
}