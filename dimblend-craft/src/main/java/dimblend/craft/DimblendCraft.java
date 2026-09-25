package dimblend.craft;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import dimblend.craft.recipe.RecipeEditReloadListener;

/**
 * Dimblend Craft：TrainTripWorld 整合包专用的运行时配方调整 mod。
 *
 * <p>本 mod 不注册任何方块/物品，唯一的职责是在每次数据包重载
 * （初始加载与 /reload）后，按 docs/配方修改需求.md 的规则表对 Create 及其
 * 附属 mod、TACZ 的配方做批量修改与删除。</p>
 */
@Mod(DimblendCraft.MODID)
public class DimblendCraft {
    public static final String MODID = "dimblend_craft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DimblendCraft(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new RecipeEditReloadListener(event));
        LOGGER.info("[配方] 已注册配方编辑重载监听器");
    }
}