package dimblend.mixin.simurail;

import java.util.function.DoubleSupplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.crystaelix.simurail.api.track.TrackTypeEntry;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;

import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Two-state Simurail bogie track lock for the rotating dimension, copied from the
 * Create: Linear Bearing pattern (redstone level = lock state).
 *
 * <p>Why: the rotating dimension's corridor is an effectively endless straight line
 * along X that trains are meant to run unattended. Simurail's
 * {@code PhysicsBogeyAxle#updateLimits} releases the axle joint
 * ({@code zLimit/yLimit = Float.MAX_VALUE}) once {@code speed^2} exceeds
 * {@code factor / curvature}, so any fast curve, collision kick, or coupler jolt can
 * throw a bogie off the rails with no way to recover unattended. Returning positive
 * infinity for the two max-speed factors makes {@code speedSq > maxSpeedSq} permanently
 * false, so the joint limits keep converging to 0 and the bogie stays on the rail.
 * The lambda is non-capturing (a JVM-cached singleton), so this adds no allocation.
 *
 * <p>Two-state semantics (Linear Bearing parity): the lock engages only while the
 * bogie block senses a redstone signal — any neighbor with {@code POWERED=true}, an
 * adjacent redstone block, or {@code Level#hasNeighborSignal}, checked exactly like
 * {@code LinearMovingBlockEntity#tick}. A redstone block placed next to the bogie
 * rides along with the consist and acts as a permanent lock pin; cut the signal
 * (lever off / remove the block) and the bogie is back to vanilla behavior, free to
 * staff-drag. Unlike Linear Bearing's weld ({@code FixedConstraint} + reflective
 * {@code unDock}), engaging here only tightens the existing rail joint — no
 * constraint is created or destroyed, so lock/unlock is transient per-tick state with
 * nothing to persist, and re-railing via {@code findTrack} keeps working.
 *
 * <p>Scope note: the redirects only change the factor suppliers; every other axle
 * behavior (traction, brakes, slip, re-railing) is untouched, and outside
 * {@code dimblend:rotating} — or without signal inside it — the original suppliers
 * are returned verbatim. Track ends, gaps, and missing segments still derail via
 * {@code trackSegment == null} (joints removed) — this lock covers overspeed/curvature
 * derails only, by design. Note the signal space is shared with Simurail's own
 * brake/steer wiring: redstone placed for those near a rotating bogie also engages
 * the lock while active.
 *
 * <p>Drift note: descriptors were verified against the vendored jar
 * ({@code libs/simurail-1.21.1-0.0.0-a+ecd2dd3.jar}, commit ecd2dd3) via CFR
 * decompilation. Simurail upgrades that move the {@code lateralMaxSpeedFactor} /
 * {@code verticalMaxSpeedFactor} calls out of {@code updateLimits} make these redirects
 * fail loudly at apply time (require = 1) instead of silently unlocking — re-verify
 * the descriptors against the new jar when that happens, then refresh {@code libs/}.
 */
@Mixin(value = PhysicsBogeyAxle.class, remap = false)
public abstract class PhysicsBogeyAxleLockMixin {

    @Shadow
    @Final
    protected PhysicsBogeyBlockEntity bogey;

    @Redirect(
            method = "updateLimits",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/api/track/TrackTypeEntry;"
                            + "lateralMaxSpeedFactor()Ljava/util/function/DoubleSupplier;"
            ),
            remap = false,
            require = 1
    )
    private DoubleSupplier dimblend$lockLateralFactor(TrackTypeEntry trackType) {
        if (dimblend$lockEngaged()) {
            return () -> Double.POSITIVE_INFINITY;
        }
        return trackType.lateralMaxSpeedFactor();
    }

    @Redirect(
            method = "updateLimits",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/api/track/TrackTypeEntry;"
                            + "verticalMaxSpeedFactor()Ljava/util/function/DoubleSupplier;"
            ),
            remap = false,
            require = 1
    )
    private DoubleSupplier dimblend$lockVerticalFactor(TrackTypeEntry trackType) {
        if (dimblend$lockEngaged()) {
            return () -> Double.POSITIVE_INFINITY;
        }
        return trackType.verticalMaxSpeedFactor();
    }

    private boolean dimblend$lockEngaged() {
        Level level = this.bogey.getLevel();
        if (level == null || !DimBlendRegistries.ROTATING_LEVEL.equals(level.dimension())) {
            return false;
        }
        BlockPos pos = this.bogey.getBlockPos();
        for (Direction dir : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(dir));
            if (neighbor.hasProperty(BlockStateProperties.POWERED)
                    && neighbor.getValue(BlockStateProperties.POWERED)) {
                return true;
            }
            if (neighbor.is(Blocks.REDSTONE_BLOCK)) {
                return true;
            }
        }
        return level.hasNeighborSignal(pos);
    }
}
