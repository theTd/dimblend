package dimblend.experience.curse;

import dimblend.experience.DimBlend;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * R19 自定义负面效果注册。走 DeferredRegister，注册名 {@code dimblend_experience:cursed_poison}；
 * 客户端图标在 {@code assets/dimblend_experience/textures/mob_effect/cursed_poison.png}。
 */
public final class CurseEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, DimBlend.MODID);

    /** R19「诅咒中毒」：绑定诅咒装备脱下后 60 分钟，牛奶不可解。 */
    public static final DeferredHolder<MobEffect, MobEffect> CURSED_POISON =
            EFFECTS.register("cursed_poison", CursedPoisonMobEffect::new);

    private CurseEffects() {
    }
}
