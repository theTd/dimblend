package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * G4 传送门禁令（回家通道不管，不留后门）：
 * <ul>
 * <li>生成禁：rotating 内任何传送门点火生成一律取消
 * （{@code BlockEvent.PortalSpawnEvent}，下界门镜框搭好也点不着；
 * 末地/虚空等自定义成门若不走该事件，由下条兜底）。</li>
 * <li>离境禁：rotating 出发的跨维度旅行一律取消
 * （{@code EntityTravelToDimensionEvent}，不限目的地——下界/末地/虚空/
 * 暮色/折跃门目标一网打尽，无需枚举）。</li>
 * <li>折跃门：功能由 {@code WarpGateBlockMixin}（dimblend 目标）在任意维度
 * 直接掐断（留块），此处旅行禁令为其跨维度路径的第二道闸。</li>
 * </ul>
 * <p>失败静默（无提示）；指令/死亡等非门手段不动（旅行事件只拦实体维度旅行，
 * 传送指令与重生走其他路径）。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class PortalBan {

    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.PORTAL_BAN.get()) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!Config.PORTAL_BAN.get()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        event.setCanceled(true);
    }

    private PortalBan() {
    }
}
