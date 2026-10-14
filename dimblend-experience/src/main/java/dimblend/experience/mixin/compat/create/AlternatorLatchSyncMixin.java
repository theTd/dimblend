package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dimblend.experience.compat.cca.AlternatorOverstressLatchAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D8 锁存标志的客户端同步（2026-10-14 新条目）：目标为 Create
 * {@code KineticBlockEntity} 自声明的 {@code write}/{@code read}（与
 * {@code KineticUnloadedShareMixin} 同例），handler 以 instanceof 限定实现了
 * {@link AlternatorOverstressLatchAccess} 的 BE（即 CCA 交流发电机）——
 * 本类不直接引用 CCA 类，plugin 按 createaddition 在场性过滤。
 *
 * <p>只在客户端包（{@code clientPacket=true}）写读：磁盘 NBT 不落锁存
 * （与 D7 同例——纯内存，区块卸载/重进按新进入重判）。服务端在锁存进出时
 * 调 {@code sendData()} 推送；客户端回写字段后，护目镜应力段随
 * {@code calculateStressApplied} 归零整段隐藏，显示与实际入网需求一致。</p>
 */
@Mixin(KineticBlockEntity.class)
public abstract class AlternatorLatchSyncMixin {

    @Unique
    private static final String dimblend$LATCH_TAG = "DimBlendAlternatorOverstressLatched";

    @Inject(method = "write", at = @At("TAIL"))
    private void dimblend$writeLatchToClientPacket(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket) {
            return;
        }
        if ((Object) this instanceof AlternatorOverstressLatchAccess access) {
            compound.putBoolean(dimblend$LATCH_TAG, access.dimblend$isOverstressLatched());
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void dimblend$readLatchFromClientPacket(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket) {
            return;
        }
        if ((Object) this instanceof AlternatorOverstressLatchAccess access) {
            access.dimblend$setOverstressLatchedFromSync(compound.getBoolean(dimblend$LATCH_TAG));
        }
    }
}
