package dimblend.mixin.simurail;

import org.joml.Vector3dc;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import com.crystaelix.simurail.api.math.Basis3dc;
import com.crystaelix.simurail.api.math.Frame3d;
import com.crystaelix.simurail.api.math.SimurailMath;
import com.crystaelix.simurail.config.SimurailConfig;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.track.CurvedTrackSegment;
import com.crystaelix.simurail.content.track.TrackSegment;
import com.crystaelix.simurail.content.track.TrackSegmentHelper;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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
 * and squeezes them to zero once the axle settles. What makes it derail are its exits:
 * <ul>
 * <li>the lateral overspeed release, the vertical (crest) overspeed release, and the
 * one-sided free lift of {@code allowVerticalMovement} — the {@code updateLimits} redirect
 * replaces every {@code setLimit} with the symmetric captive limit from
 * {@link SimurailBogeyLockRules#captiveHalfWidth}: zero once settled, vanilla's own squeeze
 * while still settling (so a freshly railed axle never snaps), and "hold where it is" if
 * vanilla tried to release this step. A settled axle stays pinned at zero even when a
 * heavy step stretches it: the solver pulls it straight back, as Linear Bearing's channel
 * would;</li>
 * <li>the geometric track check in {@code updateTrack}: every physics step an axle outside
 * a narrow window around its rail line loses the track, which removes the joint. A hard
 * limit is still a solver constraint, so a larger physics step (one substep per tick) or a
 * heavy hit stretches it past that for a moment. The {@code updateTrack} redirects keep the
 * current segment, and accept the next connected one, while the axle is within
 * {@link SimurailBogeyLockRules#CAPTIVE_REACH} of the rail line; the joint then pulls it
 * back. Along the rail, curve pieces overlap their neighbours by
 * {@link SimurailBogeyLockRules#CURVE_PIECE_OVERLAP} so a stretched axle on the outside of a
 * bend never falls between two pieces; straight segments keep vanilla's span check. The
 * curve piece span is measured straight along the piece rather than along the axle's own
 * vertical as vanilla does; the two only differ for an axle tilted far over, which the
 * rail joint (roll locked, pitch and yaw within 60 degrees) does not allow;</li>
 * <li>the periodic re-search, which uses the narrow window too: it keeps the current
 * segment while real track (a track block or curve) is still found at the point on the
 * segment's line the axle is held to. Otherwise vanilla's search runs.</li>
 * </ul>
 * Joint motors are never touched. Other dimensions keep vanilla Simurail unchanged.
 *
 * <p>Scope: the end of the track still derails. When the axle runs past its segment and no
 * connected segment follows (track end, gap, broken track), vanilla's track search decides,
 * the same as a Linear Bearing slider running off the end of its casing; the periodic
 * re-search also hands back to vanilla once there is no track under the held point (past a
 * buffer, or a removed track whose edge is still in Create's graph). Vanilla's
 * span margin on a straight segment is 1/32 of its length, so on a long straight the
 * overrun can last until that re-search (at most {@code axleTrackRecheckTime}), as in
 * vanilla. The lock is rigid, as Linear Bearing is: a physics-staff drag on a railed bogie
 * fights hard limits rather than a spring.
 *
 * <p>Drift note: call sites were verified against the vendored jar
 * ({@code libs/simurail-1.21.1-0.0.0-a+ecd2dd3.jar}, commit e28e2c9 on the ecd2dd3 line) via
 * CFR decompilation and javap: {@code updateLimits} has exactly three {@code setLimit} calls
 * (two {@code LINEAR_Y}, one {@code LINEAR_Z}) and runs after {@code updateTrack} has
 * refreshed {@code trackFrame}/{@code trackAxleFrame} and after it has updated
 * {@code yFixed}/{@code zFixed}; {@code updateTrack} has one {@code TrackSegment.inSegmentRange}
 * call (current segment), one {@code CurvedTrackSegment.inLineRange} and one
 * {@code CurvedTrackSegment.inProjectionRange} (curve chain), one {@code TrackSegment.inLineRange}
 * and one {@code TrackSegment.inProjectionRange} (graph neighbour), and exactly one
 * {@code findTrack} call after its second read of {@code trackRecheckTime} (the periodic
 * re-search; the earlier {@code findTrack} follows a failed next-segment search). Every
 * redirect pins its match count with {@code require} and {@code allow}, so a Simurail
 * upgrade that changes these makes the mixin fail loudly at apply time instead of silently
 * unlocking — re-verify against the new jar, then refresh {@code libs/}.
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

    @Shadow
    protected TrackSegment trackSegment;

    @Shadow
    protected Pose3dc trackSubLevelPose;

    @Shadow
    protected double trackRecheckTime;

    @Shadow
    protected abstract void findTrack(ServerSubLevel subLevel);

    @Redirect(
            method = "updateLimits",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/physics/constraint/GenericConstraintHandle;"
                            + "setLimit(Ldev/ryanhcode/sable/api/physics/constraint/ConstraintJointAxis;DD)V"
            ),
            remap = false,
            require = 3,
            allow = 3
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

    /** Current segment: still on it while within its span and the captive reach of its line. */
    @Redirect(
            method = "updateTrack",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/content/track/TrackSegment;"
                            + "inSegmentRange(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Z)Z"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private boolean dimblend$captiveSegmentRange(TrackSegment segment, Vector3dc position, Vector3dc vertical,
                                                 boolean inverted) {
        if (!dimblend$inRotatingDimension()) {
            return segment.inSegmentRange(position, vertical, inverted);
        }
        return dimblend$withinCaptiveSpan(segment, position, vertical) && dimblend$withinCaptiveReach(segment, position);
    }

    /** Next piece of the same curve: line check widened to the captive reach. */
    @Redirect(
            method = "updateTrack",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/content/track/CurvedTrackSegment;"
                            + "inLineRange(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Z)Z"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private boolean dimblend$captiveCurveLineRange(CurvedTrackSegment segment, Vector3dc position, Vector3dc vertical,
                                                   boolean inverted) {
        if (!dimblend$inRotatingDimension()) {
            return segment.inLineRange(position, vertical, inverted);
        }
        return dimblend$withinCaptiveReach(segment, position);
    }

    /** Next piece of the same curve: span check with the curve piece overlap. */
    @Redirect(
            method = "updateTrack",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/content/track/CurvedTrackSegment;"
                            + "inProjectionRange(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)Z"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private boolean dimblend$captiveCurveSpan(CurvedTrackSegment segment, Vector3dc position, Vector3dc vertical) {
        if (!dimblend$inRotatingDimension()) {
            return segment.inProjectionRange(position, vertical);
        }
        return dimblend$withinCaptiveSpan(segment, position, vertical);
    }

    /** Next segment along the track graph: line check widened to the captive reach. */
    @Redirect(
            method = "updateTrack",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/content/track/TrackSegment;"
                            + "inLineRange(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Z)Z"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private boolean dimblend$captiveGraphLineRange(TrackSegment segment, Vector3dc position, Vector3dc vertical,
                                                   boolean inverted) {
        if (!dimblend$inRotatingDimension()) {
            return segment.inLineRange(position, vertical, inverted);
        }
        return dimblend$withinCaptiveReach(segment, position);
    }

    /** Next segment along the track graph: span check with the curve piece overlap for curves. */
    @Redirect(
            method = "updateTrack",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/content/track/TrackSegment;"
                            + "inProjectionRange(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;)Z"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private boolean dimblend$captiveGraphSpan(TrackSegment segment, Vector3dc position, Vector3dc vertical) {
        if (!dimblend$inRotatingDimension()) {
            return segment.inProjectionRange(position, vertical);
        }
        return dimblend$withinCaptiveSpan(segment, position, vertical);
    }

    /**
     * Periodic re-search: a locked axle keeps its segment while real track is still under the
     * point it is held to. Vanilla's search uses the narrow on-rail window and would drop a
     * stretched axle; a track end or a removed track still goes through it.
     */
    @Redirect(
            method = "updateTrack",
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lcom/crystaelix/simurail/content/bogey/PhysicsBogeyAxle;trackRecheckTime:D",
                            opcode = Opcodes.GETFIELD,
                            ordinal = 1,
                            remap = false
                    )
            ),
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/content/bogey/PhysicsBogeyAxle;"
                            + "findTrack(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V"
            ),
            remap = false,
            require = 1,
            allow = 1
    )
    private void dimblend$captiveRecheck(PhysicsBogeyAxle axle, ServerSubLevel subLevel) {
        if (this.trackSegment != null && dimblend$inRotatingDimension() && dimblend$trackUnderHeldPoint(subLevel)) {
            this.trackRecheckTime = SimurailConfig.SERVER.physics.axleTrackRecheckTime.get();
            return;
        }
        this.findTrack(subLevel);
    }

    /**
     * Whether real track lies at the point on the current segment's line this axle is held
     * to: vanilla's own track search, run from that point (on the rail, so inside its narrow
     * window), must find a track block or curve there. Past a track end, or on a removed
     * track, it finds none. {@code trackAxleFrame} and {@code trackSubLevelPose} are this
     * step's, for this segment: any segment change earlier in the step restarts the re-search
     * timer.
     */
    @Unique
    private boolean dimblend$trackUnderHeldPoint(ServerSubLevel subLevel) {
        TrackSegment segment = this.trackSegment;
        double t = segment.projectT(this.trackAxleFrame.position);
        if (!Double.isFinite(t)) {
            return false;
        }
        Pose3dc pose = this.trackSubLevelPose == null ? SimurailMath.POSE_I : this.trackSubLevelPose;
        Frame3d held = segment.frame(t, new Frame3d()).transform(pose);
        return TrackSegmentHelper.findTrackSegment(subLevel.getLevel(), subLevel, held.position, held.direction,
                held.vertical, this.bogey.isInverted(), this.bogey.getOptions().type.trackTypes()) != null;
    }

    /** Along-the-rail check: curve pieces with their overlap, straight segments as vanilla. */
    @Unique
    private static boolean dimblend$withinCaptiveSpan(TrackSegment segment, Vector3dc position, Vector3dc vertical) {
        if (segment instanceof CurvedTrackSegment) {
            return SimurailBogeyLockRules.withinCurvePieceSpan(segment.projectT(position));
        }
        return segment.inProjectionRange(position, vertical);
    }

    @Unique
    private static boolean dimblend$withinCaptiveReach(TrackSegment segment, Vector3dc position) {
        Basis3dc basis = segment.basis();
        double lateral = SimurailMath.projectTLinePoint(segment.start(), basis.lateral(), position);
        double vertical = SimurailMath.projectTLinePoint(segment.start(), basis.vertical(), position);
        return SimurailBogeyLockRules.withinCaptiveReach(lateral, vertical);
    }

    @Unique
    private boolean dimblend$inRotatingDimension() {
        Level level = this.bogey.getLevel();
        return level != null && DimBlendRegistries.ROTATING_LEVEL.equals(level.dimension());
    }
}
