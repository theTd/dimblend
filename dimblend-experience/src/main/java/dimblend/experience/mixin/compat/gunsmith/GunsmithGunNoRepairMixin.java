package dimblend.experience.mixin.compat.gunsmith;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 兜底层（针对<b>枪</b>）：让 taCZ 的 {@code ModernKineticGunItem} 对"锭修枪"说不。
 *
 * <p>gundb 的 {@code ModernKineticGunMixin}（priority 默认 1000）把 {@code isRepairable}
 * （{@code damage >= 0}，恒真）和 {@code isValidRepairItem}（{@code modifiers.repairItem().test(...)}，
 * 即各种锭）合并进了这个物品类；原版 {@code AnvilMenu#createResult} 的"锭修装备"分支就是靠
 * {@code isValidRepairItem} 触发的，于是枪能拿锭白嫖耐久，而且 gundb 自己的
 * {@code AnvilMenuMixin} 只在这种情况<b>下</b>才动手改成本——等于两层都在放行。</p>
 *
 * <p>本 mixin 从物品自身这一层直接短路：枪"不可被任何材料修复"，
 * 于是原版和 gundb 的锭修分支都进不来。真正的修枪只有
 * {@link GunsmithAnvilMenuMixin} 的一条路：枪 + {@code create:precision_mechanism}。</p>
 *
 * <p><b>priority 1100 是硬要求</b>：这两个方法不在 taCZ 的 {@code ModernKineticGunItem} 里，
 * 是 gundb 的 mixin 合并出来的，必须等它先应用。gundb 缺席时由
 * {@code DimBlendMixinPlugin} 跳过本 mixin。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.item.ModernKineticGunItem", priority = 1100, remap = false)
public abstract class GunsmithGunNoRepairMixin {

    @Inject(method = "isRepairable(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"),
            cancellable = true, require = 1, allow = 1)
    private void dimblend$neverRepairable(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "isValidRepairItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$noIngotRepair(ItemStack stack, ItemStack candidate, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
