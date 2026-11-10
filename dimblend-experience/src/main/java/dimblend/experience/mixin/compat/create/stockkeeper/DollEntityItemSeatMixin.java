package dimblend.experience.mixin.compat.create.stockkeeper;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.create.stockkeeper.DollSeatPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve upstream entity data, placement checks, item consumption, sound and game event. */
@Mixin(targets = "com.github.ysbbbbbb.kaleidoscopedoll.item.DollEntityItem")
public abstract class DollEntityItemSeatMixin {
    @ModifyExpressionValue(method = "useOn",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;relative(Lnet/minecraft/core/Direction;)Lnet/minecraft/core/BlockPos;"),
            require = 1, allow = 1)
    private BlockPos dimblend$placeOverSeat(BlockPos original, UseOnContext context) {
        return DollSeatPlacement.canPlace(context) ? context.getClickedPos().above() : original;
    }

    @WrapOperation(method = "useOn",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),
            require = 1, allow = 1)
    private boolean dimblend$seatNewDoll(Level level, Entity doll, Operation<Boolean> original, UseOnContext context) {
        boolean added = original.call(level, doll);
        if (added) DollSeatPlacement.seatSpawnedDoll(context, doll);
        return added;
    }
}