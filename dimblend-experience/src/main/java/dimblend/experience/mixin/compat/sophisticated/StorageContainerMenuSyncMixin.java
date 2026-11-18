package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.InitialStackSyncState;
import dimblend.experience.compat.sophisticated.SophisticatedSyncPolicy;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 精妙背包服务端槽位镜像的兜底重同步 + 客户端初始同步状态标记。
 *
 * <p>目标：{@code net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase}
 * （成员签名经 {@code javap -p -s} 核对；该类不在本模块编译期 classpath，故 {@code @Pseudo}
 * 按字符串匹配）。</p>
 *
 * <p>上游 {@code reloadUpgradeControl} 按位置前缀把旧的 {@code lastUpgradeSlots} /
 * {@code remoteUpgradeSlots} 回填进重建后的槽位表。升级模块插入/移除/交换后槽位数与顺序会变，
 * 镜像与真实槽位长期错位；而 {@code synchronizeSlotToRemote} 只在"镜像 ≠ 真实值"时发包，
 * 于是合成/锻造/切石升级里的物品在客户端不出现，直到关屏重开触发全量重发。列数升级触发的
 * {@code refreshAllSlots} 又会让客户端延迟一个包往返才重建槽位表，重建前收到的按槽号发来的
 * 包会被 {@code setItem}（{@code getTotalSlotsNumber() > slotId}）静默丢弃，同样永久失步。</p>
 *
 * <p>修复方式：服务端在这些重建之后开一个短暂的强制重发窗口（40 tick）。窗口内把"恰好相等"
 * 的镜像临时替换成永不匹配的哨兵堆栈，让上游原逻辑自己重发真实堆栈（不重写私有方法体），
 * 客户端重建完成后仍在窗口内的包必然补齐。窗口只在服务端生效。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase", remap = false)
public abstract class StorageContainerMenuSyncMixin implements InitialStackSyncState {

    @Shadow
    @Final
    private Player player;

    @Unique
    private int dimblend$forceResyncTicks;

    @Unique
    private boolean dimblend$initialStacksSynced;

    @Unique
    private boolean dimblend$openTimeCaptured;

    @Unique
    private long dimblend$openGameTime;

    @Override
    public boolean dimblend$isInitialStackSyncSettled() {
        if (dimblend$initialStacksSynced) {
            return true;
        }
        if (player == null || player.level() == null) {
            return false;
        }
        long now = player.level().getGameTime();
        if (!dimblend$openTimeCaptured) {
            dimblend$openTimeCaptured = true;
            dimblend$openGameTime = now;
        }
        return now - dimblend$openGameTime > SophisticatedSyncPolicy.INITIAL_SYNC_GRACE_TICKS;
    }

    /** 客户端收到一次性全量槽位包（{@code SyncContainerStacksPayload} → 本方法）。 */
    @Inject(method = "initializeContents(ILjava/util/List;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$markInitialStacksSynced(int stateId, List<ItemStack> items, ItemStack carried,
            CallbackInfo ci) {
        dimblend$initialStacksSynced = true;
    }

    /** 升级模块插入/移除/交换导致升级槽位表重建：开强制重发窗口。 */
    @Inject(method = "reloadUpgradeControl(Z)V", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$resyncAfterUpgradeReload(boolean removeOpenTabId, CallbackInfo ci) {
        dimblend$requestFullResync();
    }

    /** 列数升级改变存储槽数：服务端立即重建，客户端延迟重建，开强制重发窗口。 */
    @Inject(method = "updateColumnsTaken(I)V", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$resyncAfterColumnChange(int columnsChange, CallbackInfo ci) {
        if (columnsChange != 0) {
            dimblend$requestFullResync();
        }
    }

    /**
     * 窗口内把与真实值相等的镜像改成哨兵，使上游的"值变化才发包"判断必然成立。
     *
     * <p>不动 {@code suppressRemoteUpdates}：上游若正抑制更新则不会覆盖哨兵，下一次正常
     * 广播仍会因镜像不匹配而补发。</p>
     */
    @Inject(
            method = "synchronizeSlotToRemote(ILnet/minecraft/world/item/ItemStack;Ljava/util/function/Supplier;Lnet/minecraft/core/NonNullList;I)V",
            at = @At("HEAD"),
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$forceSlotResync(int slotIndex, ItemStack slotStack, Supplier<ItemStack> slotStackCopy,
            NonNullList<ItemStack> remoteSlotsCollection, int slotIndexOffset, CallbackInfo ci) {
        if (dimblend$forceResyncTicks <= 0) {
            return;
        }
        if (slotIndex < 0 || slotIndex >= remoteSlotsCollection.size()) {
            return;
        }
        if (ItemStack.matches(remoteSlotsCollection.get(slotIndex), slotStack)) {
            remoteSlotsCollection.set(slotIndex,
                    new ItemStack(Items.STONE, SophisticatedSyncPolicy.RESYNC_SENTINEL_COUNT));
        }
    }

    /** 每个服务端 tick 的广播末尾递减窗口；客户端不会走到这里。 */
    @Inject(method = "broadcastChanges()V", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$tickForceResync(CallbackInfo ci) {
        if (dimblend$forceResyncTicks > 0) {
            --dimblend$forceResyncTicks;
        }
    }

    @Unique
    private void dimblend$requestFullResync() {
        if (player == null || player.level() == null || player.level().isClientSide()) {
            return;
        }
        dimblend$forceResyncTicks = SophisticatedSyncPolicy.FORCE_RESYNC_TICKS;
    }
}
