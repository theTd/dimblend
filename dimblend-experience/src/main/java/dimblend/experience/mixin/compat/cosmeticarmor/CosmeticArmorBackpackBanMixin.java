package dimblend.experience.mixin.compat.cosmeticarmor;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.experience.compat.accessories.AccessoriesSurvivalRules;
import dimblend.experience.compat.toolbelt.ToolBeltBackpackGuard;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 生存模式下禁止把背包塞进"外观盔甲槽"：{@code ContainerCosArmor$3#mayPlace(ItemStack)Z}。
 *
 * <p>为什么是外观槽：Cosmetic Armor Reworked 的外观槽只是贴图来源，物品<b>不</b>提供任何属性；
 * 背包（{@code sophisticatedbackpacks}）一旦塞进外观槽，就能绕过"背包只能背在身上"的容量/掉落规则
 * （背包不占身体槽也能开界面）。用户口径（2026-11-25）：生存模式下列外观盔甲槽不接受背包，
 * 创造/旁观照旧放行，与 Accessories 的生存判定同一个口径。</p>
 *
 * <p>{@code @Pseudo} + {@code targets} + {@code remap = false}：可选模组；匿名内部类的
 * {@code val$player} 是编译期合成的 final 字段（{@code javap -p} 核实），shadow 它即可拿到玩家。
 * 只挡 {@code mayPlace}，不碰 {@code set}——已经塞进去的旧存档不丢东西，只是不能再放新的。</p>
 */
@Pseudo
@Mixin(targets = "lain.mods.cos.impl.inventory.ContainerCosArmor$3", remap = false)
public abstract class CosmeticArmorBackpackBanMixin {

    @Shadow
    @Final
    private Player val$player;

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true, remap = false,
            require = 1, allow = 1)
    private void dimblend$refuseBackpackInSurvival(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (AccessoriesSurvivalRules.isSurvival(val$player) && ToolBeltBackpackGuard.isBackpackItem(stack)) {
            cir.setReturnValue(false);
        }
    }
}
