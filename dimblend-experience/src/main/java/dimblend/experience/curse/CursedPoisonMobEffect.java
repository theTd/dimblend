package dimblend.experience.curse;

import java.util.Set;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.EffectCure;

/**
 * R19「诅咒中毒」：行为与 1.21.1 中毒一致——血量 &gt;1 时每 {@code 25 >> amplifier} tick 扣 1 点魔法伤害，
 * 等级 amplifier 0（界面显示 I），时长 72000 tick（60 分钟）。
 *
 * <p>牛奶不可解：1.21.1 没有 vanilla 的 curative-item 列表，NeoForge 通过
 * {@link net.neoforged.neoforge.common.extensions.IMobEffectExtension#fillEffectCures} 收集 cure 集合，
 * 默认是 {@code {MILK, PROTECTED_BY_TOTEM}}。这里覆写为空集合，所以牛奶与不死图腾都解除不了；
 * 这是需求明确接受的副作用。死亡重生不走 cure——新建的 ServerPlayer 默认不复制效果，照常清空。</p>
 */
public class CursedPoisonMobEffect extends MobEffect {

    /** 60 分钟 = 72000 tick。 */
    public static final int DURATION_TICKS = 72000;
    /** 等级 amplifier 0（界面显示 I）。 */
    public static final int AMPLIFIER = 0;
    /** 与 MobEffects.POISON 相同的液体颜色，粒子也同色。 */
    public static final int COLOR = 8889187;

    public CursedPoisonMobEffect() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (CurseRules.cursedPoisonDamages(entity.getHealth())) {
            entity.hurt(entity.damageSources().magic(), 1.0F);
        }
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return CurseRules.cursedPoisonTicksOn(duration, amplifier);
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // 故意不填：牛奶（MILK）与不死图腾（PROTECTED_BY_TOTEM）都解不掉诅咒中毒。
    }
}
