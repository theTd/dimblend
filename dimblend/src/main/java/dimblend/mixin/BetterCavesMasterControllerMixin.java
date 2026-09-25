package dimblend.mixin;

import java.util.function.Function;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.yungnickyoung.minecraft.bettercaves.worldgen.controller.MasterController", remap = false)
public abstract class BetterCavesMasterControllerMixin {
    @Inject(method = "carve", at = @At("HEAD"), cancellable = true)
    private void dimblend$skipDisabledAquifer(
            ChunkAccess chunk,
            Function<?, ?> biomeGetter,
            CarvingMask mask,
            Aquifer aquifer,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (aquifer == null || !hasLiquidRegions(aquifer)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean hasLiquidRegions(Aquifer aquifer) {
        for (Class<?> iface : aquifer.getClass().getInterfaces()) {
            if ("com.yungnickyoung.minecraft.bettercaves.duck.ILiquidRegionsProvider".equals(iface.getName())) {
                try {
                    return iface.getMethod("bettercaves$getLiquidRegions").invoke(aquifer) != null;
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            }
        }
        return false;
    }
}
