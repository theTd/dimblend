package dimblend.experience.curse;

import dimblend.experience.DimBlend;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 自定义负面效果注册。走 DeferredRegister，注册名 {@code dimblend_experience:binding_curse}；
 * 客户端图标在 {@code assets/dimblend_experience/textures/mob_effect/binding_curse.png}
 * （原版凋零图标的逐字节副本，粒子颜色另由效果颜色常量决定）。
 */
public final class CurseEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, DimBlend.MODID);

    /** 绑定诅咒：脱下最后一件绑定诅咒装备后 60 分钟，生命上限压到 2，牛奶/图腾不可解。 */
    public static final DeferredHolder<MobEffect, MobEffect> BINDING_CURSE =
            EFFECTS.register("binding_curse", BindingCurseMobEffect::new);

    private CurseEffects() {
    }
}
