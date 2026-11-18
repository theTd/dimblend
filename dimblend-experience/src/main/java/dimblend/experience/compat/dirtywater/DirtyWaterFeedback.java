package dimblend.experience.compat.dirtywater;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 脏水拒收的 hotbar 反馈（"太脏了 至少先过滤一下"）。
 *
 * <p>{@link #notifyNow} 用于一次右键一次的路径（汤锅/茶壶/酒桶），{@link #notifyThrottled}
 * 用于每个槽位变化都跑一遍的合成台提示——同一玩家 20 tick 内只提示一次，避免刷屏。
 * 按 {@link Player} 实例做弱引用键，玩家退出即回收，不需要额外的清理任务。</p>
 */
public final class DirtyWaterFeedback {

    /** hotbar 提示翻译键（zh_cn：太脏了 至少先过滤一下）。 */
    public static final String MESSAGE_KEY = "message.dimblend_experience.dirty_water_rejected";

    private static final long THROTTLE_TICKS = 20L;

    private static final Map<Player, Long> LAST_SENT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 手持路径：{@code user} 不是玩家（假玩家/生物）时静默拒绝。 */
    public static void notify(LivingEntity user) {
        if (user instanceof Player player) {
            notifyNow(player);
        }
    }

    /** 立即在 hotbar 上方显示拒绝提示。 */
    public static void notifyNow(Player player) {
        player.displayClientMessage(Component.translatable(MESSAGE_KEY), true);
    }

    /** 同一玩家 {@value #THROTTLE_TICKS} tick 内只提示一次（合成台/背包网格这类高频路径用）。 */
    public static void notifyThrottled(Player player, long gameTime) {
        synchronized (LAST_SENT) {
            Long last = LAST_SENT.get(player);
            if (last != null && gameTime - last < THROTTLE_TICKS) {
                return;
            }
            LAST_SENT.put(player, gameTime);
        }
        notifyNow(player);
    }

    private DirtyWaterFeedback() {
    }
}
