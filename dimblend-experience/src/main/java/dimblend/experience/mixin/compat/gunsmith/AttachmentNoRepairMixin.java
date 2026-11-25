package dimblend.experience.mixin.compat.gunsmith;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 兜底层：让 taCZ 的 {@code AttachmentItem} 对铁砧一律"不可修复"。
 *
 * <p>gundb 的 {@code AttachmentItemMixin} 把 {@code isRepairable} 写成
 * {@code damage >= 0}（恒真），于是任何配件都能被"各种锭"修；而铁砧那层的规则
 * （{@link GunsmithAnvilMenuMixin}，priority 900）虽然已经先清空结果，
 * 这里再加一道"物品自身说不"的短路，防别的 mod 直接调
 * {@code isValidRepairItem}/{@code isRepairable} 抄近路。</p>
 *
 * <p><b>priority 1100 是硬要求</b>：目标方法 {@code isRepairable}/{@code isValidRepairItem}
 * 本身<b>不在</b> {@code AttachmentItem} 里，是 gundb 的 mixin（priority 1000）合并进去的；
 * 必须等它先应用完，本 mixin 才找得到这两个方法。gundb 缺席时由
 * {@code DimBlendMixinPlugin} 直接跳过本 mixin。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.item.AttachmentItem", priority = 1100, remap = false)
public abstract class AttachmentNoRepairMixin {

    @Inject(method = "isRepairable(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"),
            cancellable = true, require = 1, allow = 1)
    private void dimblend$neverRepairable(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "isValidRepairItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$noRepairMaterial(ItemStack stack, ItemStack candidate, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
