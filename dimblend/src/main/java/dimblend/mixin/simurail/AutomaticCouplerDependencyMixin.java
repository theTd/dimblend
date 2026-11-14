package dimblend.mixin.simurail;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards {@link AutomaticCouplerBlockEntity#sable$getConnectionDependencies()} when a coupled
 * partner sub-level is unloaded or has already been removed.
 *
 * <p>Upstream {@code PhysicsBogeyBlockEntity#sable$getConnectionDependencies()} skips {@code null}
 * results from {@code SubLevelContainer#getSubLevel(UUID)}, whereas
 * {@code AutomaticCouplerBlockEntity#sable$getConnectionDependencies()} calls
 * {@code List.of(container.getSubLevel(partnerSubLevelID))} unconditionally. Whenever a
 * non-force-loaded partner unloads first (including during {@code ServerLevel#save} /
 * {@code SubLevelHoldingChunkMap#processUnloads}), is removed without uncoupling, or has not yet
 * loaded on world start, {@code List.of(null)} throws a {@link NullPointerException} inside
 * {@code ServerSubLevelContainer#collectForceLoadedSubLevels()}.
 */
@Mixin(value = AutomaticCouplerBlockEntity.class, remap = false)
public abstract class AutomaticCouplerDependencyMixin {

    @Shadow
    protected UUID partnerSubLevelID;

    @Inject(
            method = "sable$getConnectionDependencies",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1
    )
    private void dimblend$skipMissingPartnerDependency(CallbackInfoReturnable<Iterable<SubLevel>> cir) {
        if (this.partnerSubLevelID == null) {
            return;
        }
        Level level = ((AutomaticCouplerBlockEntity) (Object) this).getLevel();
        SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        if (container == null || container.getSubLevel(this.partnerSubLevelID) == null) {
            cir.setReturnValue(List.of());
        }
    }
}
