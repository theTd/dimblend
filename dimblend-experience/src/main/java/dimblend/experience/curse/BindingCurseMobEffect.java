package dimblend.experience.curse;

import java.util.Set;
import dimblend.experience.DimBlend;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.EffectCure;

/**
 * 「绑定诅咒」效果：把玩家的生命上限压到 2 点（基础 20 + modifier -18），时长 72000 tick。
 *
 * <p>为什么不走 {@code MobEffect#addAttributeModifier}：那条路只登记模板，再由原版
 * {@code addAttributeModifiers(AttributeMap, int)} 用 {@code addPermanentModifier} 落地；
 * 这里自己覆写 {@code addAttributeModifiers} / {@code removeAttributeModifiers}，挂的是同一类永久
 * modifier（理由见 {@link #applyHealthModifier(AttributeInstance)}：只有永久 modifier 会写进存档）。
 * 生命周期由 {@code LivingEntity#onEffectAdded / onEffectRemoved / onEffectUpdated} 调用：
 * 上限一旦下降，原版 {@code refreshDirtyAttributes} → {@code onAttributeUpdated} 会把当前血量夹到新上限
 * （vanilla 1.21.1 {@code LivingEntity.java:1049-1055}，调用点在 {@code aiStep} 末尾 {@code :2439}）。</p>
 *
 * <p>牛奶与不死图腾解不掉：覆写 NeoForge 的 {@code fillEffectCures} 且不填任何 cure。
 * 死亡重生不复制效果，照常清空；{@code /effect clear} 属于管理员手段，不额外挡。</p>
 */
public class BindingCurseMobEffect extends MobEffect {

    /** 60 分钟 = 72000 tick。 */
    public static final int DURATION_TICKS = 72000;
    /** 等级 amplifier 0（界面显示 I）。 */
    public static final int AMPLIFIER = 0;
    /** 原版玩家基础生命上限。 */
    public static final double VANILLA_BASE_MAX_HEALTH = 20.0D;
    /** modifier 数值：20 - 18 = 2。 */
    public static final double HEALTH_MODIFIER_AMOUNT = ModifierSpec.AMOUNT;
    /** 效果期间的生命上限。 */
    public static final float FORCED_MAX_HEALTH = (float) (VANILLA_BASE_MAX_HEALTH + HEALTH_MODIFIER_AMOUNT);
    /** 与 MobEffects.WITHER 相同的效果颜色，粒子也同色（vanilla {@code MobEffects.java:80}）。 */
    public static final int COLOR = 7561558;

    public BindingCurseMobEffect() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }

    /** modifier 规范：id 固定、-18 点、操作 ADD_VALUE（20 - 18 = 2）。每次调用返回新实例，内容不可变。 */
    public static AttributeModifier healthModifier() {
        return ModifierSpec.create();
    }

    @Override
    public void addAttributeModifiers(AttributeMap attributes, int amplifier) {
        AttributeInstance maxHealth = attributes.getInstance(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        applyHealthModifier(maxHealth);
    }

    @Override
    public void removeAttributeModifiers(AttributeMap attributes) {
        AttributeInstance maxHealth = attributes.getInstance(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.removeModifier(ModifierSpec.id());
        }
    }

    /**
     * 先按 id 摘掉旧 modifier，再挂永久 modifier（幂等：效果时长刷新会再进 {@code addAttributeModifiers}
     * 一次，不先摘会抛 {@code IllegalArgumentException}，也绝不叠加成 -36）。
     *
     * <p>为什么必须是 {@code addPermanentModifier} 而不是 {@code addTransientModifier}：
     * {@code AttributeInstance#save()} 只序列化 {@code permanentModifiers}（vanilla 1.21.1
     * {@code AttributeInstance.java:178-193}），而 {@code LivingEntity#readAdditionalSaveData} 重载时
     * 只还原 effect 实例、不重跑 {@code addAttributeModifiers}（{@code LivingEntity.java:720-730}）。
     * 瞬态 modifier 因此会在重登/重启后丢失：效果图标与倒计时还在，生命上限却悄悄回到 20。
     * 原版 {@code MobEffect#addAttributeModifiers} 也是"先 removeModifier 再 addPermanentModifier"
     * （{@code MobEffect.java:151-159}），这正是原版效果能跨重登保留的原因。</p>
     */
    static void applyHealthModifier(AttributeInstance maxHealth) {
        maxHealth.removeModifier(ModifierSpec.id());
        maxHealth.addPermanentModifier(healthModifier());
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // 故意不填：牛奶（MILK）与不死图腾（PROTECTED_BY_TOTEM）都解不掉绑定诅咒。
    }

    /**
     * modifier 规范的唯一来源（id / 数值 / 操作）。
     *
     * <p>为什么单独拆一个静态嵌套类：{@code MobEffect} 的静态初始化会碰 {@code BuiltInRegistries}
     * （{@code CODEC = BuiltInRegistries.MOB_EFFECT.holderByNameCodec()}），而普通 JUnit 里没有跑
     * FML bootstrap，那一步直接 {@code ExceptionInInitializerError}（{@code Bootstrap.bootStrap()} 同样过不去）。
     * 本类不继承 {@code MobEffect}，单测能独立加载它并断言 id / -18 / ADD_VALUE；
     * 生产代码也只从这里造 modifier，保证"单测验到的"就是"钩子挂上去的"。</p>
     */
    static final class ModifierSpec {

        /** modifier 的固定 id：先摘再挂靠它做幂等，永不叠加。 */
        static final String ID = DimBlend.MODID + ":binding_curse_max_health";
        /** 20 - 18 = 2。 */
        static final double AMOUNT = -18.0D;
        /** 加法操作，不是倍率。 */
        static final AttributeModifier.Operation OPERATION = AttributeModifier.Operation.ADD_VALUE;

        private ModifierSpec() {
        }

        static ResourceLocation id() {
            return ResourceLocation.parse(ID);
        }

        static AttributeModifier create() {
            return new AttributeModifier(id(), AMOUNT, OPERATION);
        }
    }
}
