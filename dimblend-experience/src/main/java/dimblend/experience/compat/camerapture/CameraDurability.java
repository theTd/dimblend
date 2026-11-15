package dimblend.experience.compat.camerapture;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;

/**
 * Camera durability defaults and paper reloading.
 *
 * <p>Default components give every camera {@code MAX_DAMAGE = 64} and {@code DAMAGE = 64}: a freshly
 * crafted camera is empty and needs film. Right-clicking the camera in an inventory with paper on
 * the cursor removes one paper and one damage point; at full durability the click is still
 * cancelled so the cursor paper is never swapped with the camera.
 */
public final class CameraDurability {
    private static final ResourceLocation CAMERA_ID =
            ResourceLocation.fromNamespaceAndPath("camerapture", "camera");

    /** Both defaults are registered from the mod constructor only when CameraCapture is present. */
    public static void modifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        event.modify(BuiltInRegistries.ITEM.get(CAMERA_ID), builder -> builder
                .set(DataComponents.MAX_DAMAGE, CameraFilmRules.MAX_DURABILITY)
                .set(DataComponents.DAMAGE, CameraFilmRules.MAX_DURABILITY));
    }

    /** Paper on the cursor, right-clicked on a camera: load one shot. */
    public static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        if (event.getClickAction() != ClickAction.SECONDARY) {
            return;
        }
        ItemStack carried = event.getCarriedItem();
        ItemStack camera = event.getStackedOnItem();
        if (!carried.is(Items.PAPER) || !isCamera(camera)) {
            return;
        }
        Slot slot = event.getSlot();
        if (!slot.allowModification(event.getPlayer())) {
            return;
        }
        if (camera.getDamageValue() > 0) {
            camera.setDamageValue(camera.getDamageValue() - 1);
            carried.shrink(1);
            slot.setChanged();
        }
        // Full camera too: the default click would swap the cursor paper with the camera.
        event.setCanceled(true);
    }

    private static boolean isCamera(ItemStack stack) {
        return !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(CAMERA_ID);
    }

    private CameraDurability() {
    }
}
