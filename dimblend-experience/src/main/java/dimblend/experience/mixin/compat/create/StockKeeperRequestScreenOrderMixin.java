package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import dimblend.experience.compat.create.StockKeeperOrderMatcher;
import java.util.List;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仓库管理员订单的重新校验：整段替换 {@code StockKeeperRequestScreen#revalidateOrders()}。
 *
 * <p>Create 原实现有两个叠加缺陷（6.0.10）：</p>
 * <ol>
 *   <li>数量夹取用 {@code InventorySummary#getCountOf}（严格
 *       {@code isSameItemSameComponents}）。第三方物品被 tooltip/渲染路径懒挂组件后，订单
 *       副本与最新快照不再逐位相等 → 数量归 0 → {@code renderItemEntry} 不绘制 count==0
 *       的条目，订单看着被"自动取消"；</li>
 *   <li>{@code invalid} 是 {@code HashSet<BigItemStack>}，而 {@code BigItemStack#hashCode}
 *       含 {@code count}：先改 {@code entry.count} 再 {@code invalid.remove(entry)} 用的是
 *       新哈希，删不掉，0 数量僵尸条目继续占订单槽（9 格满后无法再加单）。</li>
 * </ol>
 *
 * <p>这里改用 {@code removeIf} 线性清理；严格匹配为 0 时向快照做一次有界宽松匹配
 * （见 {@link StockKeeperOrderMatcher}），命中后把快照里的真实 ItemStack 回写进订单，
 * 使后续 {@code getCountOf} 与发往服务端的 {@code PackageOrderWithCrafts} 指向同一份
 * 堆栈。快照整体为空时不动订单：那是服务端摘要异常时的瞬时空包，不是物品真的没了。</p>
 */
@Mixin(StockKeeperRequestScreen.class)
public abstract class StockKeeperRequestScreenOrderMixin {

    @Shadow private StockTickerBlockEntity blockEntity;

    @Shadow private List<BigItemStack> itemsToOrder;

    @Shadow private List<List<BigItemStack>> currentItemSource;

    @Inject(method = "revalidateOrders()V", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$revalidateOrders(CallbackInfo ci) {
        ci.cancel();
        InventorySummary summary = blockEntity == null ? null : blockEntity.getLastClientsideStockSnapshotAsSummary();
        if (currentItemSource == null || summary == null || summary.isEmpty()) {
            return;
        }
        List<BigItemStack> snapshotStacks = summary.getStacks();
        for (BigItemStack entry : itemsToOrder) {
            int available = summary.getCountOf(entry.stack);
            if (available <= 0) {
                Optional<StockKeeperOrderMatcher.Match> match =
                        StockKeeperOrderMatcher.findUniqueMatch(entry.stack, snapshotStacks);
                if (match.isPresent()) {
                    entry.stack = match.get().stack();
                    available = match.get().available();
                }
            }
            entry.count = available <= 0 ? 0 : Math.min(available, entry.count);
        }
        itemsToOrder.removeIf(entry -> entry.count <= 0);
    }
}
