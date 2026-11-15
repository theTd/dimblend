package dimblend.experience.mixin.compat.camerapture;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.compat.camerapture.CameraFilmRules;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import me.chrr.camerapture.item.CameraItem;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Camera film is durability: the camera is not enchantable or repairable, cannot be stripped or
 * repaired at a grindstone, never breaks, and the viewfinder reads the shots loaded in the camera
 * instead of the paper in the inventory.
 *
 * <p>All public instance methods below override {@code Item} or NeoForge's item hooks; the
 * {@link ModifyReturnValue} handlers are private static injection handlers. CameraCapture is gated
 * in {@code DimBlendMixinPlugin}, so none of this loads without it.
 */
@Mixin(value = CameraItem.class, remap = false)
public abstract class CameraItemRulesMixin {
    /** No enchanting-table eligibility. */
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    /** No enchantability; the stack-sensitive NeoForge hook delegates here for non-books. */
    public int getEnchantmentValue() {
        return 0;
    }

    /** No anvil book application. */
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        return false;
    }

    /** No enchantment may be applied through any mechanism. */
    public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        return false;
    }

    /** Never offered as a primary item for an enchantment. */
    public boolean isPrimaryItemFor(ItemStack stack, Holder<Enchantment> enchantment) {
        return false;
    }

    /** No anvil material repair. */
    public boolean isRepairable(ItemStack stack) {
        return false;
    }

    /** No grindstone repair or enchantment stripping. */
    public boolean canGrindstoneRepair(ItemStack stack) {
        return false;
    }

    /**
     * Any external damage is clamped to at most all-but-one point, so vanilla's
     * {@code ItemStack.hurtAndBreak} can never reach {@code maxDamage} and shrink the stack. The
     * film rules set damage directly instead.
     */
    public <T extends LivingEntity> int damageItem(
            ItemStack stack, int amount, @Nullable T entity, Consumer<Item> onBroken) {
        return CameraFilmRules.clampedDamage(stack.getMaxDamage(), stack.getDamageValue(), amount);
    }

    /** The viewfinder's paper counter shows the shots loaded in the held camera. */
    @ModifyReturnValue(method = "getPaperInInventory", at = @At("RETURN"), require = 1, allow = 1)
    private static int dimblend$filmInCamera(int original, Player player) {
        return CameraFilmRules.filmInCamera(player);
    }

    /** An empty camera blocks the shot in both modes unless creative durability bypass is enabled. */
    @ModifyReturnValue(method = "canTakePicture", at = @At("RETURN"), require = 1, allow = 1)
    private static boolean dimblend$enforceDurability(boolean original, Player player) {
        return CameraFilmRules.canTakePicture(player);
    }
}
