package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import dimblend.experience.compat.create.SteamEngineExhaustAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 32 秒排气窗口的落盘/同步：从 {@code SmartBlockEntity} 这一层注入。
 *
 * <p>为什么不注入 {@code SteamEngineBlockEntity}：它<b>不</b>覆写 {@code write/read}
 * （{@code javap -p} 核实，方法来自 {@code SmartBlockEntity} → {@code SyncedBlockEntity}），
 * Mixin 只能改目标类<b>自己声明</code>的方法，所以必须打在父类上、再用
 * {@code instanceof} 过滤到蒸汽引擎。代价是每个 Create BE 存取时多一次 instanceof。</p>
 *
 * <p>键 {@code DimBlendExhaustRemaining}，值是剩余 tick；{@code write} 的
 * {@code clientPacket=true} 路径（{@code SyncedBlockEntity#writeClient} → {@code write}）
 * 会把剩余量同步给客户端，{@code notifyUpdate()} 在开始/结束各触发一次。</p>
 */
@Mixin(SmartBlockEntity.class)
public abstract class SmartBlockEntityExhaustNbtMixin {

    @Inject(method = "write", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$writeExhaustRemaining(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (!((Object) this instanceof SteamEngineBlockEntity)) {
            return;
        }
        int remaining = SteamEngineExhaustAccess.remaining((SmartBlockEntity) (Object) this);
        if (remaining > 0) {
            tag.putInt(SteamEngineExhaustAccess.NBT_KEY, remaining);
        } else {
            tag.remove(SteamEngineExhaustAccess.NBT_KEY);
        }
    }

    @Inject(method = "read", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$readExhaustRemaining(CompoundTag tag, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (!((Object) this instanceof SteamEngineBlockEntity)) {
            return;
        }
        // 存档里没有这个键就是 0（未排气），setRemaining(0) 会顺手把内存条目清掉。
        SteamEngineExhaustAccess.setRemaining((SmartBlockEntity) (Object) this,
                tag.getInt(SteamEngineExhaustAccess.NBT_KEY));
    }
}
