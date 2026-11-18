package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.InitialStackSyncState;
import dimblend.experience.compat.sophisticated.SophisticatedSyncPolicy;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
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
 *
 * <p>{@code clicked} 的注入补的是另一条洞：左键拾取移除升级模块的路径上游不调
 * {@code setChanged()}，服务端根本不重建（见 {@code dimblend$rebuildContainersAfterUpgradeSlotClick}）。
 * 重建后 {@code reloadUpgradeControl} TAIL 在服务端直接补发一次全量，值/图标/附加槽位信息
 * 不再等 40 tick。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase", remap = false)
public abstract class StorageContainerMenuSyncMixin implements InitialStackSyncState {

    @Shadow
    @Final
    private Player player;

    @Shadow(remap = false)
    protected abstract boolean isUpgradeSlot(int slotId);

    @Shadow(remap = false)
    public abstract Slot getSlot(int slotId);

    @Shadow(remap = false)
    public abstract void sendAllDataToRemote();

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

    /**
     * 升级模块插入/移除/交换导致升级槽位表重建：服务端立即补发一次全量（槽位值 + 图标 +
     * 附加槽位信息），再开强制重发窗口兜底后续 tick 的变化。
     */
    @Inject(method = "reloadUpgradeControl(Z)V", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$resyncAfterUpgradeReload(boolean removeOpenTabId, CallbackInfo ci) {
        dimblend$requestFullResync();
        if (player != null && player.level() != null && !player.level().isClientSide()) {
            this.sendAllDataToRemote();
        }
    }

    /**
     * 左键拾取移除升级模块后强制走一次槽位表重建。
     *
     * <p>上游 {@code clicked} 的移除分支只 {@code slot.remove()} + {@code onTake()}，从不调用
     * {@code setChanged()}：服务端因此不重建 {@code upgradeContainers/upgradeSlots}，而客户端
     * 收到模块槽空包后经 {@code StorageUpgradeSlot#set → setChanged} 自行重建，两侧槽位数与索引
     * 从此分叉，之后的设置槽包被丢弃或写进错误容器（"物品消失、重开背包才好"）。</p>
     *
     * <p>用 {@code @At("RETURN")} 而不是 {@code TAIL}：{@code clicked} 在 mayPlace/mayPickup
     * 失败、占用槽校验等分支会提前 return，{@code TAIL} 只匹配最后一个 RETURN。这里是多注入点，
     * 因此<b>不能</b>写 {@code allow = 1}；{@code isUpgradeSlot} 守卫让其他分支保持 no-op——
     * 放置分支自己已调过 {@code setChanged()}，{@code reloadUpgradeControl} 在无变化时是空操作。</p>
     */
    @Inject(
            method = "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("RETURN"),
            remap = false,
            require = 1)
    private void dimblend$rebuildContainersAfterUpgradeSlotClick(
            int slotId, int dragType, ClickType clickType, Player clickPlayer, CallbackInfo ci) {
        if (slotId >= 0 && this.isUpgradeSlot(slotId)) {
            Slot slot = this.getSlot(slotId);
            if (slot != null) {
                slot.setChanged();
            }
        }
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
