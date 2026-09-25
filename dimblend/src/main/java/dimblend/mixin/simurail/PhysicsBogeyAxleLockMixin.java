package dimblend.mixin.simurail;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.config.SimurailConfig;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Two-state Simurail bogie track lock for the rotating dimension: redstone level =
 * lock state, copied from the Create: Linear Bearing pattern, but implemented as a
 * compliant spring instead of a hard limit.
 *
 * <p>Why a spring, not a hard limit: Linear Bearing's rail "bite" is a magnetic
 * spring-damper force field ({@code MagnetPair#applyForces}: stiffness × displacement
 * plus 2ζ√(mk) damping, acceleration-capped, applied through a queued force group) —
 * never a position-level hard constraint — which is exactly why staff-dragging it
 * feels heavy yet smooth: spring fights spring. Simurail's axle rail joint instead
 * squeezes {@code LINEAR_Y/Z} limits toward absolute 0 every tick, so a staff drag
 * (itself a PD spring) fights a rigid wall: jitter, snagging, and the bogie visibly
 * crushed into the rail head. This redirect replaces the hard squeeze with a stiff
 * PD spring toward the track frame whenever the lock is engaged: strong grip at
 * rest, compliant stretch under a staff drag, smooth return on release.
 *
 * <p>Two-state semantics (Linear Bearing parity): the lock engages only while the
 * bogie block senses a redstone signal — any neighbor with {@code POWERED=true}, an
 * adjacent redstone block, or {@code Level#hasNeighborSignal}, checked exactly like
 * {@code LinearMovingBlockEntity#tick}. A redstone block placed next to the bogie
 * rides along with the consist and acts as a permanent lock pin; cut the signal and
 * the bogie is back to vanilla behavior, free to staff-drag. Unlike Linear Bearing's
 * weld ({@code FixedConstraint} + reflective {@code unDock}), engaging here never
 * creates or destroys a constraint — motors and limits are re-applied every physics
 * tick anyway — so lock/unlock is transient per-tick state with nothing to persist,
 * and re-railing via {@code findTrack} keeps working.
 *
 * <p>Guarantee structure (two layers): the spring is the feel, the residual hard
 * capture net is the guarantee. {@code LOCK_CAPTURE_LIMIT} (0.3) sits far inside
 * {@code TrackSegment#inLineRange}'s derail thresholds (lateral ±0.5, vertical
 * -1.5…+0.375), so the bogie cannot leave the rail even if the spring saturates.
 * When engaged, the {@code !checkVertical} free-lift branch ({@code allowVerticalMovement})
 * is capped too — an engaged lock bites downward travel as well as upward, by design.
 * Track ends, gaps, and missing segments still derail via {@code trackSegment == null}
 * (joints removed before this code runs) — this lock covers overspeed/curvature
 * derails only, by design. Note the signal space is shared with Simurail's own
 * brake/steer wiring: redstone placed for those near a rotating bogie also engages
 * the lock while active.
 *
 * <p>Constant rationale: stiffness/damping mirror Simurail's own suspension spring
 * formula (stiffness = f², damping = 2ζf with ζ = 1.0) at f = 20Hz — double the stock
 * 10Hz suspension frequency for a "bite" feel, same damping regime as the well-tested
 * pivot springs. The backend normalizes by mass the same way it does for those, so
 * this stays in a known-good regime; {@code LOCK_SPRING_MAX_FORCE} is 10× the stock
 * pivot spring cap. Joint motors are persistent state that vanilla writes only once
 * at joint creation (damper-only), so the disengaged path re-applies the
 * creation-equivalent motor — otherwise the lock spring would linger after the signal
 * is cut. All four lock constants are first-guess playtest knobs, not derived optima.
 *
 * <p>Ordering note: {@code updateAxles} runs {@code updateJoint} (damper-only motors,
 * set once at joint creation) before {@code updateLimits}, so motors applied here win
 * for the tick and are refreshed every tick — no fight with joint creation. This
 * redirect covers all three {@code setLimit} sites in {@code updateLimits} (two
 * {@code LINEAR_Y}, one {@code LINEAR_Z}; angular limits live in
 * {@code updateJoint} and are untouched).
 *
 * <p>Drift note: call sites were verified against the vendored jar
 * ({@code libs/simurail-1.21.1-0.0.0-a+ecd2dd3.jar}, commit ecd2dd3) via CFR
 * decompilation. Simurail upgrades that add/remove {@code setLimit} calls in
 * {@code updateLimits} make this redirect fail loudly at apply time (require = 3)
 * instead of silently unlocking — re-verify the count against the new jar when that
 * happens, then refresh {@code libs/}.
 */
@Mixin(value = PhysicsBogeyAxle.class, remap = false)
public abstract class PhysicsBogeyAxleLockMixin {

    private static final double LOCK_SPRING_STIFFNESS = 400.0;
    private static final double LOCK_SPRING_DAMPING = 40.0;
    private static final double LOCK_SPRING_MAX_FORCE = 100000.0;
    private static final double LOCK_CAPTURE_LIMIT = 0.3;

    @Shadow
    @Final
    protected PhysicsBogeyBlockEntity bogey;

    @Redirect(
            method = "updateLimits",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/constraint/GenericConstraintHandle;"
                            + "setLimit(Ldev/ryanhcode/sable/api/physics/constraint/ConstraintJointAxis;DD)V"
            ),
            remap = false,
            require = 3
    )
    private void dimblend$springLockLimits(GenericConstraintHandle joint, ConstraintJointAxis axis, double min, double max) {
        if (dimblend$lockEngaged()
                && (axis == ConstraintJointAxis.LINEAR_Y || axis == ConstraintJointAxis.LINEAR_Z)) {
            joint.setMotor(axis, 0.0, LOCK_SPRING_STIFFNESS, LOCK_SPRING_DAMPING, true, LOCK_SPRING_MAX_FORCE);
            joint.setLimit(axis, -LOCK_CAPTURE_LIMIT, LOCK_CAPTURE_LIMIT);
            return;
        }
        if (axis == ConstraintJointAxis.LINEAR_Y || axis == ConstraintJointAxis.LINEAR_Z) {
            // Joint motors are persistent state and vanilla only writes them once at joint
            // creation (damper-only). Restore the creation-equivalent motor so disengaging
            // the lock truly returns to vanilla behavior instead of leaving the lock spring
            // behind. The value is read live so config reloads are respected.
            joint.setMotor(axis, 0.0, 0.0, dimblend$passiveLinearDamping(), false, 0.0);
        }
        joint.setLimit(axis, min, max);
    }

    private static double dimblend$passiveLinearDamping() {
        return (Double) SimurailConfig.SERVER.physics.axlePassiveLinearDamping.get();
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
