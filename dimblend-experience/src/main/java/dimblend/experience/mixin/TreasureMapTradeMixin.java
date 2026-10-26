package dimblend.experience.mixin;

import javax.annotation.Nullable;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A13 探险图交易禁令（全局规则，不限维度）：原版 {@code VillagerTrades.TreasureMapForEmeralds}
 * 的 {@code getOffer} 在 HEAD 直接返回 null，<b>任何调用者都不再进入
 * {@code findNearestMapStructure}</b>（原版 1.21.1 {@code VillagerTrades.java:1822-1840}
 * 先取 {@code trader.level()}，再按 100 区块半径扫结构）。
 *
 * <p>为什么必须拦类本身而不是只改交易表：制图师交易表有两份（原版 / 交易重平衡
 * {@code TypeSpecificTrade} 包装），包装类的 {@code getOffer} 会把请求转回本类的
 * {@code getOffer}；只删表项覆盖不到「模组/数据包直接 new 本类」与包装变体，而本注入
 * 覆盖全部调用路径。返回 null 后原版 {@code AbstractVillager.addOffersFromItemListings}
 * 会跳过该条并继续抽下一条（{@code AbstractVillager.java:236-242}），所以同一级仍能拿到
 * 其余交易，无需在 {@code VillagerMasterRules.rebuildTrades} 里再过滤一遍。
 *
 * <p>2026-10-26 实测：单次收编触发 4 次该搜索，dimblend:rotating 里一个被收编的制图师
 * 让服务端单 tick 卡死 430.4 秒（stack 全程在 {@code StructureCheck.checkStart}）。
 * 目标类是包私有嵌套类，只能按 {@code targets} 字符串指定。
 */
@Mixin(targets = "net.minecraft.world.entity.npc.VillagerTrades$TreasureMapForEmeralds")
public abstract class TreasureMapTradeMixin {

    @Inject(method = "getOffer", at = @At("HEAD"), cancellable = true)
    private void dimblend$banExplorerMapOffer(Entity trader, RandomSource random,
            CallbackInfoReturnable<MerchantOffer> cir) {
        cir.setReturnValue((MerchantOffer) null);
    }
}
