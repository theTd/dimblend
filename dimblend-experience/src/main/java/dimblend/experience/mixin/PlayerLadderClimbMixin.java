package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dimblend.experience.exploration.LadderClimbRules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 全局「按住空格才爬梯」（2026-11-25 LOCKED #1 {@code ladder_global}）。
 *
 * <p>只改写 {@code handleRelativeFrictionAndCalculateMovement} 里那一次
 * {@code onClimbable()} 取值：玩家没按跳跃就当不在攀爬位，于是
 * {@code (horizontalCollision || jumping) && onClimbable()} 这条上爬分支失效；
 * 同一条件里的粉雪+皮革靴分支与 {@code handleOnClimbable()} 的下滑钳制都不变。</p>
 *
 * <p>目标方法内 {@code onClimbable()} 只出现一次（另一个调用点在
 * {@code handleOnClimbable()} 自己的方法体里），故 {@code require/allow = 1} 可钉死。</p>
 */
@Mixin(LivingEntity.class)
public abstract class PlayerLadderClimbMixin {

    @Shadow
    private boolean jumping;

    @ModifyExpressionValue(
            method = "handleRelativeFrictionAndCalculateMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;onClimbable()Z"
            ),
            require = 1,
            allow = 1
    )
    private boolean dimblend$climbOnlyWhileJumping(boolean onClimbable) {
        LivingEntity self = (LivingEntity) (Object) this;
        return LadderClimbRules.climbPositionActive(onClimbable, self instanceof Player, this.jumping);
    }
}
