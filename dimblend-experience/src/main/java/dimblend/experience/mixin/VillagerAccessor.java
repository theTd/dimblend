package dimblend.experience.mixin;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * G1 村民交易补全入口：复用原版按等级取 2 条的逻辑（{@code addOffersFromItemListings}），
 * 收编时逐级调用即可补全 1–5 级全部交易，新人新口味与 mod 增补一并生效。
 * 实现体在父类 {@code AbstractVillager}（{@code Villager} 未重写，直接 Mixin {@code Villager}
 * 会因找不到目标方法导致启动期 {@code InvalidAccessorException} 崩溃），故目标为父类；
 * {@code Villager} 继承后原有 {@code (VillagerAccessor) villager} 强转仍有效。
 */
@Mixin(AbstractVillager.class)
public interface VillagerAccessor {

    @Invoker("addOffersFromItemListings")
    void dimblend$addOffers(MerchantOffers offers, VillagerTrades.ItemListing[] listings, int count);
}
