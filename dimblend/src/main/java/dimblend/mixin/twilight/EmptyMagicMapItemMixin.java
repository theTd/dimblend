package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import twilightforest.item.EmptyMagicMapItem;

@Mixin(value = EmptyMagicMapItem.class, remap = false)
public abstract class EmptyMagicMapItemMixin {
    @Redirect(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/tags/TagKey;)Z",
                    remap = true
            )
    )
    private boolean dimblend$allowTwilightBand(
            Holder<?> holder,
            TagKey<?> tag,
            Level level,
            Player player,
            InteractionHand hand
    ) {
        if (TwilightBand.isTwilightPos(level, player.blockPosition())) {
            return true;
        }
        if (TwilightBand.isRotating(level)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Holder<Object> typed = (Holder<Object>) holder;
        @SuppressWarnings("unchecked")
        TagKey<Object> typedTag = (TagKey<Object>) tag;
        return typed.is(typedTag);
    }
}
