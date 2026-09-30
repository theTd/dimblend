package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dimblend.experience.compat.create.KineticUnloadedShare;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

/**
 * Create 应力网络未加载份额修正（机制见 {@link KineticUnloadedShare}）。
 *
 * <p>读档（{@code read} RETURN，非客户端包、首 tick 前）快照本 BE 的存档份额：
 * 理论转速、{@code AddedStress}、{@code AddedCapacity}，并置"待结算"。
 * 首 tick {@code initialize} HEAD 清"待结算"——份额照常由 Create 的 addSilently 扣回。
 * 首 tick 前若网络归属第一次真的改变（{@code setNetwork} HEAD，新旧 id 不同）或 BE 被拆
 * （{@code remove} HEAD），它不会再经 addSilently 回存档网络，于此按读档快照补扣并清
 * "待结算"。区块卸载不走 remove()，份额留在 unloaded 属正确口径，不动。</p>
 *
 * <p>无开关：只消除 Create 自身账本的重复计数，不改任何玩法；三个方法均为
 * {@code KineticBlockEntity} 自声明（子类 override 均调 super），字段 shadow 也只取
 * 目标类自声明字段。</p>
 */
@Mixin(KineticBlockEntity.class)
public abstract class KineticUnloadedShareMixin {

    @Shadow
    protected float lastStressApplied;

    @Shadow
    protected float lastCapacityProvided;

    /** 读档份额尚未由 addSilently 或本 mixin 结算。 */
    @Unique
    private boolean dimblend$sharePending;

    /** 已跑过 initialize（首 tick）；之后的重读不再置"待结算"。 */
    @Unique
    private boolean dimblend$initialized;

    @Unique
    private float dimblend$savedSpeed;

    @Unique
    private float dimblend$savedStressApplied;

    @Unique
    private float dimblend$savedCapacityProvided;

    @Inject(method = "read", at = @At("RETURN"))
    private void dimblend$snapshotSavedShare(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (clientPacket || this.dimblend$initialized) {
            return;
        }
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        // wasMoved 分支不读网络（network 保持 null），自然不置待结算
        this.dimblend$sharePending = self.hasNetwork();
        this.dimblend$savedSpeed = self.getTheoreticalSpeed();
        this.dimblend$savedStressApplied = this.lastStressApplied;
        this.dimblend$savedCapacityProvided = this.lastCapacityProvided;
    }

    @Inject(method = "initialize", at = @At("HEAD"))
    private void dimblend$handOverToAddSilently(CallbackInfo ci) {
        this.dimblend$initialized = true;
        this.dimblend$sharePending = false;
    }

    @Inject(method = "setNetwork", at = @At("HEAD"))
    private void dimblend$releaseOnEarlyNetworkChange(Long networkIn, CallbackInfo ci) {
        if (!this.dimblend$sharePending) {
            return;
        }
        KineticBlockEntity self = (KineticBlockEntity) (Object) this;
        if (Objects.equals(self.network, networkIn)) {
            return; // Create 同 id 直接 return，归属未变
        }
        this.dimblend$releasePendingShare(self);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void dimblend$releaseOnEarlyRemove(CallbackInfo ci) {
        if (!this.dimblend$sharePending) {
            return;
        }
        this.dimblend$releasePendingShare((KineticBlockEntity) (Object) this);
    }

    @Unique
    private void dimblend$releasePendingShare(KineticBlockEntity self) {
        this.dimblend$sharePending = false;
        KineticUnloadedShare.releaseBeforeFirstTick(self, this.dimblend$savedSpeed,
                this.dimblend$savedStressApplied, this.dimblend$savedCapacityProvided);
    }
}
