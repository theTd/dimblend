package dimblend.experience.mixin.compat.doublejump;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.compat.doublejump.DoubleJumpServerSessions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 服务端触发入口：在 {@code DoubleJumpHandler#triggerDoubleJump(ServerPlayer)} 之前按玩家校验。
 *
 * <p>上游该方法只看游戏规则，从不检查 {@code canTrigger()}，因此包重发即可无限空跳。
 * 本 mixin 先问 {@link DoubleJumpServerSessions#tryBeginJump(ServerPlayer)}：地面/水中/攀爬、
 * 飞行、鞘翅、乘骑一律拒绝，一次滞空只放行一次，并记 10 tick 冷却；通过后才调用原方法
 * （保留其位移与 {@code hurtMarked} 逻辑）。通过时状态已经记账，重复包会被直接丢弃。</p>
 *
 * <p>描述符已用 {@code javap -s} 实读为
 * {@code (Lnet/minecraft/server/level/ServerPlayer;)V}；{@code canDoubleJump(Player)Z} 用
 * {@code @Shadow} 直调，避免引用模组外的游戏规则类型。</p>
 */
@Pseudo
@Mixin(targets = "net.foufakirby.double_jump.common.DoubleJumpHandler", remap = false)
public abstract class DoubleJumpHandlerMixin {

    @Shadow(remap = false)
    private static boolean canDoubleJump(Player player) {
        throw new AssertionError("shadow");
    }

    @WrapMethod(method = "triggerDoubleJump(Lnet/minecraft/server/level/ServerPlayer;)V",
            require = 1, allow = 1)
    private static void dimblend$perPlayerTrigger(ServerPlayer player, Operation<Void> original) {
        if (!canDoubleJump(player)) {
            return;
        }
        if (!DoubleJumpServerSessions.tryBeginJump(player)) {
            return;
        }
        original.call(player);
    }
}
