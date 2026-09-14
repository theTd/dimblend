package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import java.util.Optional;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import twilightforest.init.TFDimension;

@Mixin(LocationPredicate.class)
public abstract class LocationPredicateMixin {
    @Shadow
    public abstract Optional<ResourceKey<Level>> dimension();

    @Redirect(
            method = "matches",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"
            )
    )
    private ResourceKey<Level> dimblend$twilightBandCountsAsTf(
            ServerLevel level,
            ServerLevel levelArg,
            double x,
            double y,
            double z
    ) {
        ResourceKey<Level> actual = level.dimension();
        Optional<ResourceKey<Level>> wanted = this.dimension();
        if (wanted.isPresent()
                && wanted.get() == TFDimension.DIMENSION_KEY
                && TwilightBand.isTwilightPos(level, BlockPos.containing(x, y, z))) {
            return TFDimension.DIMENSION_KEY;
        }
        return actual;
    }
}
