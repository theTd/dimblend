package dimblend.experience.mixin.client;

import com.mojang.authlib.GameProfile;
import dimblend.experience.jump.MidAirJumpEffects;
import dimblend.experience.jump.MidAirJumpPayload;
import dimblend.experience.jump.MidAirJumpRules;
import dimblend.experience.jump.MidAirJumpState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 空中二段跳（代替落地水）：起跳腾空后再次按下跳跃键，空中再跳一次、脚底 cloud，
 * 并重置下落高度。
 *
 * <p>注入点纪律（两条实机事故沉淀）：</p>
 * <ol>
 *   <li>必须在 {@code LocalPlayer.aiStep} 内部、{@code super.aiStep()} 之前判定，
 *       拿到的是「移动前」的 {@code onGround()}。旧第三方兼容在
 *       {@code ClientTickEvent.Post}（移动之后）观察，地面起跳那一 tick 玩家已被
 *       抬离地面，于是每次普通跳跃都被误判成一次空中按下沿。</li>
 *   <li>「空中按下」只能取同一 tick 内的上升沿（HEAD 记录的上一 tick
 *       {@code input.jumping} vs {@code input.tick} 刷新后的值），不能拿
 *       {@code isDown} 状态推导。</li>
 * </ol>
 *
 * <p>{@code super.aiStep()} 的字节码目标是
 * {@code Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V}（不是
 * {@code Player;aiStep()V}）。此时 {@code input.tick}、创造模式双击飞行切换、
 * 鞘翅展开都已执行，原版地面/液体起跳与移动尚未执行。</p>
 *
 * <p>继承 {@code AbstractClientPlayer} 是为了直接调用 {@code LivingEntity}
 * 的 {@code protected getJumpPower()}（声明在父类，不能对 {@code LocalPlayer}
 * 做 {@code @Shadow}）。{@code autoJumpTime} 则确实是 {@code LocalPlayer}
 * 自己声明的 private 字段。</p>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMidAirJumpMixin extends AbstractClientPlayer {

    @Shadow
    private int autoJumpTime;

    @Unique
    private final MidAirJumpState dimblend$midAirJumpState = new MidAirJumpState();

    @Unique
    private boolean dimblend$prevJumpDown;

    @Unique
    private boolean dimblend$wasFlyingAtTickStart;

    private LocalPlayerMidAirJumpMixin(ClientLevel level, GameProfile profile) {
        super(level, profile);
    }

    /** 等价原版 {@code boolean flag = this.input.jumping}：本 tick 刷新前的上一 tick 按键值与飞行态。 */
    @Inject(method = "aiStep", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$capturePrevJump(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        this.dimblend$prevJumpDown = player.input.jumping;
        this.dimblend$wasFlyingAtTickStart = player.getAbilities().flying;
    }

    @Inject(method = "aiStep",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/AbstractClientPlayer;aiStep()V",
                    shift = At.Shift.BEFORE),
            require = 1, allow = 1)
    private void dimblend$midAirJump(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        boolean inFluid = player.isInWater() || player.isInLava() || player.isInFluidType();
        boolean flyingOrJustStopped = player.getAbilities().flying || this.dimblend$wasFlyingAtTickStart;
        boolean recharge = MidAirJumpRules.shouldRecharge(player.onGround(), inFluid,
                player.onClimbable(), player.isPassenger(), flyingOrJustStopped,
                player.isFallFlying());
        boolean eligible = MidAirJumpRules.eligible(player.onGround(), flyingOrJustStopped,
                player.isFallFlying(), player.isPassenger(), player.isSpectator(), inFluid,
                player.onClimbable(), player.hasEffect(MobEffects.LEVITATION), player.isDeadOrDying());
        boolean pressed = MidAirJumpRules.pressedRisingEdge(player.input.jumping,
                this.dimblend$prevJumpDown, this.autoJumpTime);
        if (!this.dimblend$midAirJumpState.tick(recharge, eligible, pressed)) {
            return;
        }
        // 与原版同口径：JUMP_STRENGTH × blockJumpFactor + 跳跃提升；不调用
        // jumpFromGround，避免附带冲刺水平加速、统计与饥饿消耗。
        float power = this.getJumpPower();
        if (power <= 1.0E-5F) {
            return;
        }
        Vec3 movement = player.getDeltaMovement();
        player.setDeltaMovement(movement.x, (double) power, movement.z);
        player.hasImpulse = true;
        player.resetFallDistance();
        MidAirJumpEffects.spawnClientCloud(player);
        PacketDistributor.sendToServer(new MidAirJumpPayload());
    }
}
