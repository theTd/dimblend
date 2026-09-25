package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * A3/A4 远行诅咒：|z| 每跨过 256 的整数倍边界，生命上限 ×0.75 乘算叠加
 * （下限 1 点），跨界瞬间对该玩家播放 angry_villager；回到 |z|≤128 完全恢复并播放
 * happy_villager；128~256 为僵持区——不恢复也不加重；回退跨过 256 边界
 * 同样不降档（严格口径：层级只增不减，仅 |z|≤128 清零）。
 *
 * <p>层级状态存实体附件（持久化）：离开旋转维度只摘修饰符不清层级，回来原样续上；
 * 层级纯由位置推导，死亡重生后按新位置重算，无需 copyOnDeath
 * （2026-09-22 拍板维持现状：死亡重算不视为违规清零，严格口径仅约束存活移动）。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class DepthCurse {

    public static final int BOUNDARY = 256;
    public static final int HOLD_BAND = 128;
    public static final double CURSE_FACTOR = 0.75D;
    public static final double BASE_MAX_HEALTH = 20.0D;
    public static final double MIN_MAX_HEALTH = 1.0D;

    private static final ResourceLocation CURSE_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "far_curse_max_health");
    /** 上限乘数下限，使 BASE_MAX_HEALTH × factor ≥ MIN_MAX_HEALTH（=0.05）。0.75^11 < 0.05，即 tier≥11 起钳定。 */
    private static final double MIN_FACTOR = MIN_MAX_HEALTH / BASE_MAX_HEALTH;
    private static final int TICK_INTERVAL = 10;

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        if (player.tickCount % TICK_INTERVAL != 0) {
            return;
        }
        if (!RotatingDimension.is(player)) {
            // 规则只在 rotating 维度生效：摘修饰符（层级保留）
            removeModifier(player);
            return;
        }

        double absZ = Math.abs(player.getZ());

        if (Config.DEPTH_CURSE.get()) {
            int current = player.getData(ExplorationAttachments.FAR_CURSE_TIER.get());
            int target = targetTier(absZ);
            // 严格口径：仅 |z|≤128（target==0）清零；其余一律只增不减——
            // 回退跨过 256 边界（target < current）保持现状，不恢复部分生命上限。
            // target==-1（僵持区）同样被 max 接住（current 恒≥0），无需单独分支。
            int held = target == 0 ? 0 : Math.max(target, current);
            if (held != current) {
                player.setData(ExplorationAttachments.FAR_CURSE_TIER.get(), held);
                if (held > current) {
                    player.serverLevel().sendParticles(player, ParticleTypes.ANGRY_VILLAGER, false,
                            player.getX(), player.getY() + player.getEyeHeight(), player.getZ(), 8, 0.5D, 0.5D, 0.5D, 0.02D);
                }
                if (held == 0 && current > 0) {
                    player.serverLevel().sendParticles(player, ParticleTypes.HAPPY_VILLAGER, false,
                            player.getX(), player.getY() + player.getEyeHeight(), player.getZ(), 12, 0.5D, 0.5D, 0.5D, 0.05D);
                }
            }
            ensureModifier(player, held);
        } else {
            removeModifier(player);
        }
    }

    @SubscribeEvent
    public static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            removeModifier(player);
        }
    }

    /**
     * 目标层级：|z|≥256 时为跨越的边界数；|z|≤128 完全恢复（0）；
     * 中间僵持区返回 -1 表示保持现状。注意调用侧严格口径：返回值小于
     * 当前层级时同样保持现状（不降档），仅 0 允许清零。
     */
    private static int targetTier(double absZ) {
        if (absZ <= HOLD_BAND) {
            return 0;
        }
        if (absZ < BOUNDARY) {
            return -1;
        }
        return (int) (absZ / BOUNDARY);
    }

    private static double factorFor(int tier) {
        return Math.max(Math.pow(CURSE_FACTOR, tier), MIN_FACTOR);
    }

    private static void ensureModifier(ServerPlayer player, int tier) {
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        if (tier <= 0) {
            removeModifier(player);
            return;
        }
        double amount = factorFor(tier) - 1.0D;
        AttributeModifier existing = maxHealth.getModifier(CURSE_MODIFIER_ID);
        if (existing == null) {
            maxHealth.addTransientModifier(new AttributeModifier(CURSE_MODIFIER_ID, amount, Operation.ADD_MULTIPLIED_TOTAL));
        } else if (existing.amount() != amount) {
            maxHealth.removeModifier(CURSE_MODIFIER_ID);
            maxHealth.addTransientModifier(new AttributeModifier(CURSE_MODIFIER_ID, amount, Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void removeModifier(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.removeModifier(CURSE_MODIFIER_ID);
        }
    }

    private DepthCurse() {
    }
}
