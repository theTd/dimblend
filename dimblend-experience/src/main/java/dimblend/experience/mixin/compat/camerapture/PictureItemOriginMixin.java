package dimblend.experience.mixin.compat.camerapture;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.compat.camerapture.PhotoOriginData;
import java.util.UUID;
import me.chrr.camerapture.item.PictureItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Attaches the capture position recorded for this picture UUID to the finished picture stack.
 *
 * <p>{@code PictureItem.create} is the single place the server builds the stack after the upload
 * completes; writing {@link PhotoOriginData}'s compound there means vanilla syncs it to clients and
 * CameraCapture's cloning recipe keeps it like any other component. Pictures created by older
 * uploads or other paths simply have no pending entry and stay untouched.
 */
@Mixin(value = PictureItem.class, remap = false)
public abstract class PictureItemOriginMixin {
    @ModifyReturnValue(method = "create", at = @At("RETURN"), require = 1, allow = 1)
    private static ItemStack dimblend$attachPhotoOrigin(ItemStack created, String creator, UUID id) {
        return PhotoOriginData.attach(created, id);
    }
}
