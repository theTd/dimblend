package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dimblend.api.ChaseBrakeBogeyMarker;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * I20：给 Simurail 的转向架挂持久「追逐刹车车厢转向架」标记
 * （{@link ChaseBrakeBogeyMarker}），由 {@code dimblend-dragonchase} 建车时置位。
 *
 * <p>标记只影响 {@code trainBrakeSignalCap} 的例外判定（见
 * {@link dimblend.experience.compat.simurail.ChaseBrakeSignalRules}）：物理线程与
 * 服务端主线程都会读，故字段 {@code volatile}；不读方块、不查 SavedData，无数据竞争。</p>
 *
 * <p>字节码事实（运行 jar simurail-1.21.1-0.0.0-a+ecd2dd3）：
 * {@code write(CompoundTag, HolderLookup$Provider, boolean)} /
 * {@code read(CompoundTag, HolderLookup$Provider, boolean)} 都是本类重写的方法，
 * 描述符 {@code (Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V}。
 * 两处 TAIL 注入无条件读写（不按 {@code clientPacket} 分叉）：服务端存档与
 * Create 的 BE 同步包走同一对方法，客户端看到的强度与服务端一致。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyChaseBrakeMarkerMixin implements ChaseBrakeBogeyMarker {

    @Unique
    private volatile boolean dimblend$chaseBrakeBogey;

    @Override
    public boolean dimblend$isChaseBrakeBogey() {
        return this.dimblend$chaseBrakeBogey;
    }

    @Override
    public void dimblend$setChaseBrakeBogey(boolean marked) {
        this.dimblend$chaseBrakeBogey = marked;
    }

    @Inject(
            method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$writeChaseBrake(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        tag.putBoolean("DimBlendChaseBrakeBogey", this.dimblend$chaseBrakeBogey);
    }

    @Inject(
            method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$readChaseBrake(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        this.dimblend$chaseBrakeBogey = tag.getBoolean("DimBlendChaseBrakeBogey");
    }
}
