package dimblend.experience.mixin;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 潜影贝「完全闭合 = 全免伤」（2026-11-25 用户口径 {@code shulker_fully_shut}）。
 *
 * <p>原版只在闭合态免疫箭矢（{@code AbstractArrow}），其余伤害照常结算，且闭合态仍会被点燃。
 * 新口径：仅在<b>完全闭上</b>（{@code getRawPeekAmount() == 0} 且当前动画高度已降到 0）时
 * 免疫一切非 {@code BYPASSES_INVULNERABILITY} 伤害；开壳 1 秒动画期间（露出缝隙）照常可被击中。
 * 免疫仍给一次闭合闷响反馈（{@code SHULKER_HURT_CLOSED}），并在每 tick 压灭火焰，
 * 避免 TaCZ 燃烧弹在壳外挂燃烧、开壳瞬间异常掉血。</p>
 *
 * <p>上游 {@code getRawPeekAmount()} / {@code isClosed()} 与 {@code currentPeekAmount} 都是
 * private，统一走 {@link ShulkerPeekAccess}（@Invoker/@Accessor 接口）读取，不直接 shadow
 * private 成员。单测与启动冒烟都覆盖不到 {@code hurt} 的早期返回，改这里必须实机打一枪验证。</p>
 */
@Mixin(Shulker.class)
public abstract class ShulkerClosedImmunityMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$fullyShutIsImmune(DamageSource source, float amount,
            CallbackInfoReturnable<Boolean> cir) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        Shulker self = (Shulker) (Object) this;
        if (!dimblend$isFullyShut(self)) {
            return;
        }
        if (self.getRemainingFireTicks() > 0) {
            self.clearFire();
        }
        // 只有真正"打上去"的伤害源（实体直击/爆炸）才给闭合闷响；环境伤害保持安静。
        Entity direct = source.getDirectEntity();
        if (direct != null || source.is(DamageTypeTags.IS_EXPLOSION)) {
            self.level().playSound(null, self.blockPosition(), SoundEvents.SHULKER_HURT_CLOSED,
                    SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        cir.setReturnValue(false);
    }

    /** 燃烧弹在完全闭合的壳体上点不着：每 tick 把闭合态的剩余燃烧时间压回 0。 */
    @Inject(method = "tick", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$noIgnitionWhileShut(CallbackInfo ci) {
        Shulker self = (Shulker) (Object) this;
        if (self.getRemainingFireTicks() > 0 && dimblend$isFullyShut(self)) {
            self.clearFire();
        }
    }

    private static boolean dimblend$isFullyShut(Shulker shulker) {
        ShulkerPeekAccess access = (ShulkerPeekAccess) shulker;
        return access.dimblend$rawPeekAmount() == 0 && access.dimblend$currentPeekAmount() <= 0.0F;
    }
}
