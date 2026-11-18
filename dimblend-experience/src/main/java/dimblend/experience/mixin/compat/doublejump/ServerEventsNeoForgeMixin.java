package dimblend.experience.mixin.compat.doublejump;

import dimblend.experience.compat.doublejump.DoubleJumpServerSessions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端每玩家 tick：落地/入水/攀爬复位该玩家的二段跳状态，否则推进冷却，并周期性清理离线 UUID。
 *
 * <p>上游这里只对全局静态状态做 {@code onGroundReset()}（任意玩家落地会重置所有人）。
 * 原方法仍照常执行（现在只影响已废弃的全局字段），本 mixin 只追加按 UUID 隔离的状态维护。</p>
 *
 * <p>描述符已用 {@code javap -s} 实读为
 * {@code (Lnet/neoforged/neoforge/event/tick/PlayerTickEvent$Pre;)V}。</p>
 */
@Pseudo
@Mixin(targets = "net.foufakirby.double_jump.neoforge.ServerEventsNeoForge", remap = false)
public abstract class ServerEventsNeoForgeMixin {

    @Inject(
            method = "onPlayerTick(Lnet/neoforged/neoforge/event/tick/PlayerTickEvent$Pre;)V",
            at = @At("HEAD"),
            require = 1,
            allow = 1)
    private static void dimblend$trackPerPlayerState(PlayerTickEvent.Pre event, CallbackInfo ci) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            DoubleJumpServerSessions.onPlayerTick(player);
        }
    }
}
