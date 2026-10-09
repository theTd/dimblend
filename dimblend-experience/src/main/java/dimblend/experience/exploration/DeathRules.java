package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * A1 死亡经验规则（v1.13 重写，仅 rotating 维度生效）：
 * <ul>
 * <li>重生克隆时按 {@code deathExpClearRatio} 比率清除经验：新玩家经验 =
 * 死亡前（等级+进度）× (1 − 比率)，总量同比缩放。无视 keepInventory 游戏规则——
 * 开启时覆盖原版 {@code ServerPlayer#restoreFrom} 的经验复制，关闭时按比率回补，
 * 两种 gamerule 下结果一致。</li>
 * <li>经验球掉落同步取消（不能找回）：比率是唯一的经验损失规则，不掉球防止
 * 比率外找回/复制（比率 0 时若掉球会白嫖一份）。</li>
 * </ul>
 * 维度判据取死亡瞬间原实体所在维度（{@code getOriginal().level()}），重生落点在
 * 哪个维度不影响规则命中。物品栏不再由本模组处理（v1.13 起死亡保留/床遗失改道
 * 重生已取消，掉落交给 gamerule 与其他 mod）。
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class DeathRules {

    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !RotatingDimension.is(player)) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !RotatingDimension.is(event.getOriginal().level())) {
            return;
        }
        Player original = event.getOriginal();
        var scaled = DeathExperienceMath.scale(original.experienceLevel, original.experienceProgress,
                original.totalExperience, Config.DEATH_EXP_CLEAR_RATIO.get());
        Player player = event.getEntity();
        player.experienceLevel = scaled.level();
        player.experienceProgress = scaled.progress();
        player.totalExperience = scaled.total();
    }

    private DeathRules() {
    }
}
