package dimblend.mixin.simurail;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.crystaelix.simurail.api.math.Frame3d;
import com.crystaelix.simurail.api.math.SimurailMath;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dimblend.DimBlendRegistries;
import dimblend.compat.SimurailBogeyLockRules;
import net.minecraft.world.level.Level;

/**
 * Captive Simurail bogie track lock, always on in the rotating dimension, modelled on
 * Linear Bearing's rail hold: each axle is pinned to its rail like a slider in a lipped
 * channel — rigid laterally and vertically in both directions, free along the rail.
 *
 * <p>Reference behavior (Linear Bearing 1.2.6, the version shipped in the pack, verified by
 * CFR decompilation): the bearing turns into a world-side {@code linear_casing} channel
 * (base, side walls, inward lips) and assembles the block in front into a Sable sub-level
 * carrying a T-profile {@code linear_moving} slider in the same cell. Slider body, neck and
 * plate fit the channel with zero clearance, so Rapier contacts hold it permanently: no
 * trigger, no springs, no overspeed release, no lift-off; only the end of the casing lets
 * it out. (1.3.5 adds a redstone docking weld on top, which is not in the pack and freezes
 * sliding too — not what a track lock wants.)
 *
 * <p>Axle equivalent: Simurail's rail joint already limits {@code LINEAR_Y}/{@code LINEAR_Z}
 * and squeezes them to zero once the axle settles. What makes it derail are its three
 * exits — the lateral overspeed release, the vertical (crest) overspeed release, and the
 * one-sided free lift of {@code allowVerticalMovement}. In the rotating dimension this
 * redirect replaces every {@code setLimit} in {@code updateLimits} with the symmetric
 * captive limit from {@link SimurailBogeyLockRules#captiveHalfWidth}: zero once settled,
 * vanilla's own squeeze while still settling (so a freshly railed axle never snaps), and
 * "hold where it is" if vanilla tried to release this step. Joint motors are never
 * touched. Other dimensions keep vanilla Simurail unchanged.
 *
 * <p>Scope: covers overspeed and lift-off derails only. Track ends, gaps and missing
 * segments still derail via {@code trackSegment == null} (vanilla removes the joint before
 * any limit is set) — the same as a Linear Bearing slider running off the end of its
 * casing. The lock is rigid, as Linear Bearing is: a physics-staff drag on a railed bogie
 * fights hard limits rather than a spring.
 *
 * <p>Drift note: call sites were verified against the vendored jar
 * ({@code libs/simurail-1.21.1-0.0.0-a+ecd2dd3.jar}, commit ecd2dd3) via CFR
 * decompilation: {@code updateLimits} has exactly three {@code setLimit} calls (two
 * {@code LINEAR_Y}, one {@code LINEAR_Z}) and runs after {@code updateTrack} has refreshed
 * {@code trackFrame}/{@code trackAxleFrame} and after it has updated
 * {@code yFixed}/{@code zFixed}. A Simurail upgrade that changes the count makes this
 * redirect fail loudly at apply time (require = 3) instead of silently unlocking —
 * re-verify against the new jar, then refresh {@code libs/}.
 */
@Mixin(value = PhysicsBogeyAxle.class, remap = false)
public abstract class PhysicsBogeyAxleLockMixin {

    @Shadow
    @Final
    protected PhysicsBogeyBlockEntity bogey;

    @Shadow
    @Final
    protected Frame3d trackFrame;

    @Shadow
    @Final
    protected Frame3d trackAxleFrame;

    @Shadow
    protected boolean yFixed;

    @Shadow
    protected boolean zFixed;

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
    private void dimblend$captiveLimits(GenericConstraintHandle joint, ConstraintJointAxis axis, double min, double max) {
        boolean vertical = axis == ConstraintJointAxis.LINEAR_Y;
        if (!(vertical || axis == ConstraintJointAxis.LINEAR_Z) || !dimblend$inRotatingDimension()) {
            joint.setLimit(axis, min, max);
            return;
        }
        double offset = SimurailMath.projectTLinePoint(
                this.trackFrame.position,
                vertical ? this.trackFrame.vertical : this.trackFrame.lateral,
                this.trackAxleFrame.position);
        double halfWidth = SimurailBogeyLockRules.captiveHalfWidth(
                vertical ? this.yFixed : this.zFixed, min, offset);
        joint.setLimit(axis, -halfWidth, halfWidth);
    }

    @Unique
    private boolean dimblend$inRotatingDimension() {
        Level level = this.bogey.getLevel();
        return level != null && DimBlendRegistries.ROTATING_LEVEL.equals(level.dimension());
    }
}
