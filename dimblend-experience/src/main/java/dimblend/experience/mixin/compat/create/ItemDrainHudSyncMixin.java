package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.compat.create.ItemDrainGrowthBoost;
import dimblend.experience.compat.create.ItemDrainHud;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * 分液池 HUD 同步：把催熟倒计时以“下次击发的绝对 gameTime”
 * （{@link ItemDrainHud#TAG_NEXT_BOOST_AT}，-1 = 未在计时）捎进 BE 的客户端同步包。
 *
 * <p>为什么用绝对 gameTime 而不是剩余 tick 快照：上弦/击发都落在
 * {@code gameTime + countdown*10} 的精确时刻（10-tick 节拍制），客户端用自身
 * {@code level.getGameTime()} 差值即可平滑倒计时，无需逐拍发包；sendData 只在
 * 上弦/击发/水量变化等已有时机发生（{@code ItemDrainGrowthBoost} 已在上弦分支与
 * 击发点重新上弦处补 sendData，后者覆盖扣水/免费/无目标等全部击发分支）。</p>
 *
 * <p>只在 {@code clientPacket=true} 时写键，存档 NBT 不污染；倒计时是内存态，
 * 重载后由服务端重新上弦并经下一次 sendData 刷新。服务端不读此键——
 * 护目镜显示只在客户端发生，Jade 侧走自己的 server data 通道。</p>
 *
 * <p>注入点说明：{@code write}/{@code read} 均为目标类自身声明的方法（Mixin
 * 只遍历目标类 methods），单一 RETURN，TAIL 安全。客户端配置未同步等场景不读
 * Config（写侧只读服务端内存态，读侧只搬值），无 ISE 风险。</p>
 */
@Mixin(ItemDrainBlockEntity.class)
public abstract class ItemDrainHudSyncMixin implements ItemDrainHud.HasSync {

    @Unique
    private long dimblend$nextBoostAt = -1;

    @Override
    public long dimblend$nextBoostAt() {
        return this.dimblend$nextBoostAt;
    }

    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"))
    private void dimblend$writeNextBoostAt(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (!clientPacket) {
            return;
        }
        ItemDrainBlockEntity self = (ItemDrainBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        int countdown = ((ItemDrainGrowthBoost.HasState) this).dimblend$growthState().countdown;
        // countdown 只在节拍（gameTime%10==0）递减；发包可能由水量变化在非节拍时刻触发，
        // 须对齐到上一节拍再算，否则击发时刻最多提前 9 tick
        long lastBeat = level.getGameTime() - level.getGameTime() % 10;
        compound.putLong(ItemDrainHud.TAG_NEXT_BOOST_AT,
                countdown > 0 ? lastBeat + countdown * 10L : -1);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"))
    private void dimblend$readNextBoostAt(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (clientPacket && compound.contains(ItemDrainHud.TAG_NEXT_BOOST_AT)) {
            this.dimblend$nextBoostAt = compound.getLong(ItemDrainHud.TAG_NEXT_BOOST_AT);
        }
    }
}
