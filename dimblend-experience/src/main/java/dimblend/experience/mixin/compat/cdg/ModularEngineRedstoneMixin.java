package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The modular override also checks powered members beyond the controller. */
@Mixin(ModularDieselEngineBlockEntity.class)
public abstract class ModularEngineRedstoneMixin {
    @Inject(method = "enabled()Z", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$ignoreMemberRedstone(CallbackInfoReturnable<Boolean> cir) {
        if (!Config.isLoaded() || Config.DIESEL_ENGINE_BEHAVIOR.get()) {
            cir.setReturnValue(((IEngine) this).validFS());
        }
    }
}
