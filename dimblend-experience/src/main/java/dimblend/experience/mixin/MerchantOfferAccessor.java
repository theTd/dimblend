package dimblend.experience.mixin;

import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * G1 单次交易：{@code MerchantOffer.maxUses} 为 final，收编时经此改写为 1，
 * 用完即永久锁死（配合补货拦截）。
 */
@Mixin(MerchantOffer.class)
public interface MerchantOfferAccessor {

    @Mutable
    @Accessor("maxUses")
    void dimblend$setMaxUses(int maxUses);
}
