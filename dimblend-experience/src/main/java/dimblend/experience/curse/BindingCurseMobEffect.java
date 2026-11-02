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
 * 「绑定诅咒」效果：把玩家的生命上限压到 2 点（基础 20 + 瞬态 modifier -18），时长 72000 tick。
 *
 * <p>为什么不走 {@code MobEffect#addAttributeModifier}：那条路只登记模板，再由原版
 * {@code addAttributeModifiers(AttributeMap, int)} 用 {@code addPermanentModifier} 落地；需求要瞬态，
 * 所以这里覆写 {@code addAttributeModifiers} / {@code removeAttributeModifiers}，自己挂/摘瞬态 modifier。
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
    /** 瞬态 modifier 数值：20 - 18 = 2。 */
    public static final double HEALTH_MODIFIER_AMOUNT = -18.0D;
    /** 效果期间的生命上限。 */
    public static final float FORCED_MAX_HEALTH = (float) (VANILLA_BASE_MAX_HEALTH + HEALTH_MODIFIER_AMOUNT);
    /** 与 MobEffects.WITHER 相同的效果颜色，粒子也同色（vanilla {@code MobEffects.java:80}）。 */
    public static final int COLOR = 7561558;

    /** 瞬态 modifier 的固定 id：重复挂效果时先摘再挂，永不叠加。 */
    private static final ResourceLocation HEALTH_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "binding_curse_max_health");

    public BindingCurseMobEffect() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }

    @Override
    public void addAttributeModifiers(AttributeMap attributes, int amplifier) {
        AttributeInstance maxHealth = attributes.getInstance(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        // AttributeInstance#addTransientModifier 在同一 id 已存在时会抛 IllegalArgumentException，
        // 所以先摘掉旧的再挂；效果时长刷新走 onEffectUpdated，也会再进这里一次。
        maxHealth.removeModifier(HEALTH_MODIFIER_ID);
        maxHealth.addTransientModifier(new AttributeModifier(
                HEALTH_MODIFIER_ID, HEALTH_MODIFIER_AMOUNT, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void removeAttributeModifiers(AttributeMap attributes) {
        AttributeInstance maxHealth = attributes.getInstance(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.removeModifier(HEALTH_MODIFIER_ID);
        }
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // 故意不填：牛奶（MILK）与不死图腾（PROTECTED_BY_TOTEM）都解不掉绑定诅咒。
    }
}
