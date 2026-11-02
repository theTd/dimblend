package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.SimurailCouplers;
import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dimblend.experience.compat.simurail.CouplerCharge;
import dimblend.experience.compat.simurail.CouplerChargeAccess;
import dimblend.experience.compat.simurail.CouplerChargeMath;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * E10/E11 自动车钩的扳手充能状态与类型锁定。
 *
 * <ul>
 * <li><b>状态字段</b>：{@code @Unique} 布尔值，随 BE NBT 落盘并随更新包同步客户端。
 * {@code write}/{@code read} 的 {@code clientPacket} 为真走更新包
 * （{@code writeClient → write(tag, true)}），为假走存档
 * （{@code saveAdditional → write(tag, false)}），两侧都写才既落盘又同步
 * （同 {@code WhistleHornBlockEntityMixin} 口径）。</li>
 * <li><b>充能 = 通电</b>：{@code isPowered} 的 RETURN 值或上充能态。Simurail 的
 * 「已通电」语义本身就是解锁——解除伙伴、拒绝 {@code findPartner}、屏蔽
 * {@code setPartner}——断钩与拒连全部沿用既有物理路径，不另砌一套。</li>
 * <li><b>类型锁定詹式</b>：{@code cycleType} 整个取消（扳手现在只管充能），
 * {@code read} 末尾把 {@code type} 强制为 {@code KNUCKLE}。两处都要堵：只在
 * {@code cycleType} 拦，旧存档里已存成 SHIBATA 的车钩读档后仍是 SHIBATA。</li>
 * <li><b>自动取消充能</b>：服务端 {@code lazyTick} RETURN 调
 * {@link CouplerCharge#reconcile}。</li>
 * </ul>
 *
 * <p>mixin 包外不引用本类，状态一律经 {@link CouplerChargeAccess}。</p>
 */
@Mixin(AutomaticCouplerBlockEntity.class)
public abstract class AutomaticCouplerChargeMixin implements CouplerChargeAccess {

    @Unique
    private boolean dimblend$wrenchCharged;

    @Override
    public boolean dimblend$wrenchCharged() {
        return dimblend$wrenchCharged;
    }

    @Override
    public void dimblend$setWrenchCharged(boolean charged) {
        this.dimblend$wrenchCharged = charged;
    }

    @Inject(method = "isPowered", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$chargedCountsAsPowered(CallbackInfoReturnable<Boolean> cir) {
        if (this.dimblend$wrenchCharged && !cir.getReturnValueZ()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "cycleType", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$noTypeCycling(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$writeCharge(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        tag.putBoolean(CouplerChargeMath.TAG_WRENCH_CHARGED, this.dimblend$wrenchCharged);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$readChargeAndLockKnuckle(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (tag.contains(CouplerChargeMath.TAG_WRENCH_CHARGED)) {
            this.dimblend$wrenchCharged = tag.getBoolean(CouplerChargeMath.TAG_WRENCH_CHARGED);
        }
        ((AutomaticCouplerTypeAccessor) this).dimblend$setType(SimurailCouplers.KNUCKLE);
    }

    @Inject(method = "lazyTick", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$dechargeWhenAlone(CallbackInfo ci) {
        AutomaticCouplerBlockEntity self = (AutomaticCouplerBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        CouplerCharge.reconcile(level, self, this,
                ((AutomaticCouplerPartnerAccessor) self).dimblend$getPartnerPos());
    }
}