package dimblend.experience.mixin;

import dimblend.experience.curse.BindingCurseRules;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a Survival player take cursed armour off.
 *
 * <p><b>The vanilla rule being replaced.</b> {@code ArmorSlot#mayPickup} returns false when the
 * wearer is not creative and the piece carries {@code PREVENT_ARMOR_CHANGE} (the curse of
 * binding), so in Survival the piece can never leave the slot — neither by hand nor by a
 * container move. The user asked on 2026-11-03 for that to be possible.
 *
 * <p><b>Why a mixin and not a NeoForge event.</b> There is no event at this decision point: the
 * slot answers the inventory code directly, and every path (click, shift-click, hopper) goes
 * through it. Injecting at HEAD keeps the vanilla branch intact for the cases we do not own and
 * overrides exactly one verdict.
 *
 * <p><b>Why the target is named as a string.</b> {@code ArmorSlot} is package-private in
 * {@code net.minecraft.world.inventory}, so the type cannot be mentioned at compile time. The
 * handler therefore reads the stack through the public {@link Slot} supertype, which is all it
 * needs.
 *
 * <p><b>What stays the same.</b> Creative players already pass the vanilla check, so this mixin
 * changes nothing for them. Removing the piece still fires {@code LivingEquipmentChangeEvent},
 * so the mod's own 60-minute "binding curse" effect is still applied on removal — the user kept
 * that part deliberately.
 */
@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class CurseOfBindingPickupMixin extends Slot {

    /** Never called: mixins merge into the target class, which supplies the real constructor. */
    private CurseOfBindingPickupMixin() {
        super(new SimpleContainer(1), 0, 0, 0);
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void dimBlend$allowRemovingCursedArmour(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (player == null) {
            return;
        }
        ItemStack worn = this.getItem();
        if (BindingCurseRules.mayRemoveCursedArmour(player.isCreative(), worn.isEmpty(), hasCurseOfBinding(worn))) {
            cir.setReturnValue(true);
        }
    }

    private static boolean hasCurseOfBinding(ItemStack stack) {
        return !stack.isEmpty()
                && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
    }
}