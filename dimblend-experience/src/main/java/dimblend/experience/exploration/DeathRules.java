package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

/**
 * A1/A2 探索限制死亡规则（仅 rotating 维度生效）：
 * <ul>
 * <li>死亡时对整栏位做快照，取消地面掉落；重生克隆时按原槽位还原物品。</li>
 * <li>经验无条件清零——包括 keepInventory gamerule 开启时（原版会把经验一并复制给
 * 新玩家，见 {@code ServerPlayer#restoreFrom} 的 1458 行分支）。</li>
 * <li>床遗失的重生改道走 {@link PlayerRespawnPositionEvent}（NeoForge 原生事件），
 * 不用 mixin。</li>
 * </ul>
 * 快照生命周期：捕获（死亡时，规则开启+维度命中）→ 使用一次（Clone 还原）→
 * 立即清空。三个环节都以"快照非空"为唯一事实来源，保证快照绝不跨越
 * 一次死亡周期存活，从而不污染其他维度/规则关闭时的死亡行为。
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class DeathRules {

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (Config.DEATH_RULES.get() && RotatingDimension.is(player)) {
            player.setData(ExplorationAttachments.DEATH_INVENTORY.get(), DeathInventorySnapshot.capture(player));
        } else {
            // 归一化：未被本规则接管的死亡一律清空快照，堵住"第三方 mod 取消死亡
            // 后残留陈旧快照"的窄路径（下次非 rotating 死亡不会被旧快照误还原）
            player.setData(ExplorationAttachments.DEATH_INVENTORY.get(), DeathInventorySnapshot.EMPTY);
        }
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !Config.DEATH_RULES.get()
                || !RotatingDimension.is(player)) {
            return;
        }
        if (player.getData(ExplorationAttachments.DEATH_INVENTORY.get()).isEmpty()) {
            return;
        }
        event.setCanceled(true);
    }

    /**
     * A1"不能找回"：经验球掉落走独立的 {@code LivingExperienceDropEvent}（原版
     * die() 内与物品掉落分开触发），只拦 LivingDropsEvent 的话经验球照掉、可捡回。
     * 守卫与 onDrops 同型。
     */
    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !Config.DEATH_RULES.get()
                || !RotatingDimension.is(player)) {
            return;
        }
        if (player.getData(ExplorationAttachments.DEATH_INVENTORY.get()).isEmpty()) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) {
            return;
        }
        DeathInventorySnapshot snapshot = event.getOriginal().getData(ExplorationAttachments.DEATH_INVENTORY.get());
        if (snapshot.isEmpty()) {
            return;
        }
        // 快照只在死亡时刻（规则开启+维度命中）被捕获，还原不再看当前配置
        snapshot.restore(event.getEntity());
        clearExperience(event.getEntity());
        // 用完即清：快照绝不跨死亡周期存活（否则会污染后续其他维度的死亡）
        event.getEntity().setData(ExplorationAttachments.DEATH_INVENTORY.get(), DeathInventorySnapshot.EMPTY);
    }

    /**
     * A2 床遗失重生改道：原版在床失效/缺失/重生维度不存在时弹回世界出生点；
     * 本处理器在"rotating 维度内死亡 + 床不可用"时把落点替换为
     * (死亡x, 66, 5) 附近。有效床/重生锚与末地折返不受影响。
     */
    @SubscribeEvent
    public static void onRespawnPosition(PlayerRespawnPositionEvent event) {
        if (event.isFromEndFight()) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        if (!Config.DEATH_RULES.get() || !RotatingDimension.is(player)) {
            return;
        }
        DimensionTransition vanilla = event.getOriginalDimensionTransition();
        MinecraftServer server = player.level().getServer();
        boolean fellBackToWorldSpawn = vanilla.missingRespawnBlock()
                || player.getRespawnPosition() == null
                || server.getLevel(player.getRespawnDimension()) == null;
        if (!fellBackToWorldSpawn) {
            return;
        }
        ServerLevel rotating = server.getLevel(RotatingDimension.key());
        if (rotating == null) {
            return;
        }
        Vec3 spot = CorridorRespawnLocator.findRespawnPosition(rotating, player.getX());
        event.setDimensionTransition(new DimensionTransition(rotating, spot, Vec3.ZERO, 0.0F, 0.0F, DimensionTransition.DO_NOTHING));
    }

    private static void clearExperience(Player player) {
        player.experienceLevel = 0;
        player.experienceProgress = 0.0F;
        player.totalExperience = 0;
    }

    private DeathRules() {
    }
}
