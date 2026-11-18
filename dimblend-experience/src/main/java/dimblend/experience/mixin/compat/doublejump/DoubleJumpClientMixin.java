package dimblend.experience.mixin.compat.doublejump;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.compat.doublejump.DoubleJumpClientInput;
import dimblend.experience.compat.doublejump.DoubleJumpPacketBridge;
import dimblend.experience.compat.doublejump.DoubleJumpStateMachine;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * 用 {@link DoubleJumpStateMachine} 替换 {@code DoubleJumpClient#tick} 的整段判定。
 *
 * <p>上游方法把门写成 {@code canTrigger() && jumpingNow && !prevJumping && airTicks >= 2}：
 * 按住空格从地面起跳时永远等不到新的按下沿，快速二连点在离地前 2 tick 内被直接丢弃，
 * 客户端又从不置 {@code used}。本 mixin 不再调用 {@code original}，改为每 tick 采样：
 * grounded（含水中/攀爬）、禁用态（飞行/鞘翅/乘骑）、按键 down 与 click 计数；
 * 触发后立即在客户端置 {@code used}，同一滞空不再发包。</p>
 *
 * <p>目标类不在编译 classpath 上，方法描述符已用 {@code javap -s} 从
 * {@code double-jump-neoforge-1.0.4-1.21.1.jar} 实读为
 * {@code (Lnet/minecraft/client/player/LocalPlayer;)V}。</p>
 */
@Pseudo
@Mixin(targets = "net.foufakirby.double_jump.client.DoubleJumpClient", remap = false)
public abstract class DoubleJumpClientMixin {

    @Unique
    private DoubleJumpStateMachine dimblend$stateMachine;

    @WrapMethod(method = "tick(Lnet/minecraft/client/player/LocalPlayer;)V", require = 1, allow = 1)
    private void dimblend$deterministicTick(LocalPlayer player, Operation<Void> original) {
        if (player == null) {
            return;
        }
        if (this.dimblend$stateMachine == null) {
            this.dimblend$stateMachine = new DoubleJumpStateMachine();
        }
        boolean grounded = player.onGround() || player.isInWaterOrBubble() || player.onClimbable();
        boolean blocked = player.getAbilities().flying
                || player.isFallFlying()
                || player.isPassenger();
        DoubleJumpClientInput.Poll poll = DoubleJumpClientInput.poll();
        if (this.dimblend$stateMachine.tick(grounded, blocked, poll.down(), poll.clicked())) {
            DoubleJumpPacketBridge.send();
        }
    }
}
