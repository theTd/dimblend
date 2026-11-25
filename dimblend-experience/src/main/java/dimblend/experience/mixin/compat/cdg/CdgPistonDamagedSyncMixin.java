package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineDamage;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 活塞损坏态的双向 NBT 同步（2026-11-25）。
 *
 * <p>{@link CdgAttachments#ENGINE_STATE} 没有 {@code sync()} handler，客户端拿不到；
 * 而护目镜红字、以及"客户端看到坏了就不出声"都依赖这个位。Create 的
 * {@code SyncedBlockEntity#sendData()} 会经 {@code saveAdditional → write(tag, regs, false)}
 * 把数据广播给追踪区块的玩家，客户端 {@code readClient → read(tag, regs, true)} 接回，
 * 所以在三个 BE 的 {@code write}/{@code read} 尾巴上各补一个 {@code DimBlendPistonDamaged} 即可。
 * {@link CdgEngineDamage#markDamaged} 已经负责 {@code notifyUpdate()} 触发这次广播。</p>
 *
 * <p>三机的 {@code write}/{@code read} 都是 {@code protected} 且三机各自声明（{@code javap -p} 已核），
 * 签名同为 {@code (CompoundTag, HolderLookup$Provider, boolean)V}。</p>
 */
@Mixin({DieselEngineBlockEntity.class, ModularDieselEngineBlockEntity.class, HugeDieselEngineBlockEntity.class})
public abstract class CdgPistonDamagedSyncMixin {

    @Inject(method = "write", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$writePistonDamaged(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket,
            CallbackInfo ci) {
        if ((Object) this instanceof SmartBlockEntity smart) {
            CdgEngineDamage.writeNbt(smart, tag);
        }
    }

    @Inject(method = "read", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$readPistonDamaged(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket,
            CallbackInfo ci) {
        if ((Object) this instanceof SmartBlockEntity smart) {
            CdgEngineDamage.readNbt(smart, tag);
        }
    }
}
