package dimblend.experience.mixin.compat.simulated;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dimblend.experience.compat.compass.OriginCompass;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Simulated 1.3.0: leave its projected lodestone branch entirely intact. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.navigation_targets.CompassNavigationTarget", remap = false)
public abstract class NavigationCompassMixin {
    @WrapOperation(method = "getTarget(Ldev/simulated_team/simulated/content/blocks/nav_table/NavTableBlockEntity;"
            + "Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getSharedSpawnPos()Lnet/minecraft/core/BlockPos;"),
            require = 1, allow = 1, remap = false)
    private BlockPos dimblend$ordinaryCompassPointsToOrigin(Level level, Operation<BlockPos> original,
            @Local(argsOnly = true) ItemStack stack) {
        return OriginCompass.navigationSpawn(level, stack, original.call(level));
    }
}
