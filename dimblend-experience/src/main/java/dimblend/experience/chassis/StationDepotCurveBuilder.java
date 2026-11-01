package dimblend.experience.chassis;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import dimblend.api.StationDepotSites;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the depot's two Bezier merge curves and attaches them to the track graph.
 *
 * <p>Everything here follows Create's own construction in
 * {@code TrackPlacement#placeTracks}: two endpoint block positions, two curve starts
 * (block centre offset half a block along the tangent), two unit tangents, two face
 * normals, {@code primary=true}, the girder flag, and the track material. The
 * {@code BezierConnection} constructor and {@code TrackBlockEntity#addConnection} are
 * both public, which is why this needs no mixin and no reflection.
 *
 * <p><b>Tangents.</b> The siding and the main line are parallel, so there is no
 * intersection to aim at. Create handles the parallel case explicitly (angle zero in
 * {@code BezierConnection.Runtime#determineHandles}), so the tangents point straight
 * into the curve: {@code +X} at the siding end, {@code -X} at the main-line end, and
 * both are horizontal.
 *
 * <p><b>Success test.</b> After attaching, {@code getConnections()} on both endpoint
 * block entities must be non-empty and the curve length must be plausible. That is
 * checked and reported, not assumed — a curve that silently fails to attach leaves a
 * siding that looks connected and is not.
 */
public final class StationDepotCurveBuilder {
    /** Curves longer than this are rejected as a sign the material or endpoints are wrong. */
    private static final double MAX_CURVE_LENGTH = 64.0D;

    private StationDepotCurveBuilder() {
    }

    /**
     * Builds one curve, attaching it to the block entity at each end. Returns a result
     * the caller can log and retry on, never a bare boolean: "attached nothing" and
     * "attached half" are different failures.
     */
    public static Result build(ServerLevel level, int boundaryX, dimblend.api.StationDepotCurves.Curve curve) {
        BlockPos from = new BlockPos(curve.fromX(), StationDepotSites.CHASSIS_Y - 1, curve.fromZ());
        BlockPos to = new BlockPos(curve.toX(), StationDepotSites.CHASSIS_Y - 1, curve.toZ());
        // Worldgen writes every track with HAS_BE=false, and Create's TrackBlock#newBlockEntity
        // returns null in that state (TrackBlock.java: `if (!state.getValue(HAS_BE)) return null`).
        // A straight rail still joins the track graph through its shape, which is why the
        // corridor works without block entities — but a BezierConnection lives *in* the block
        // entity, so both ends have to be promoted before anything can be attached.
        ensureTrackBlockEntity(level, from);
        ensureTrackBlockEntity(level, to);
        if (!(level.getBlockEntity(from) instanceof TrackBlockEntity fromEntity)) {
            return Result.missingEnd(from);
        }
        if (!(level.getBlockEntity(to) instanceof TrackBlockEntity toEntity)) {
            return Result.missingEnd(to);
        }
        if (fromEntity.getConnections().containsKey(to)) {
            return Result.alreadyBuilt();
        }
        BlockState trackState = level.getBlockState(from);
        TrackMaterial material = materialOf(trackState);
        if (material == null) {
            return Result.noMaterial(from);
        }
        Vec3 fromStart = curveStart(from, dimblend.api.StationDepotCurves.fromTangent());
        Vec3 toStart = curveStart(to, dimblend.api.StationDepotCurves.toTangent());
        var connection = new com.simibubi.create.content.trains.track.BezierConnection(
                net.createmod.catnip.data.Couple.create(from, to),
                net.createmod.catnip.data.Couple.create(fromStart, toStart),
                net.createmod.catnip.data.Couple.create(
                        new Vec3(1.0D, 0.0D, 0.0D), new Vec3(-1.0D, 0.0D, 0.0D)),
                net.createmod.catnip.data.Couple.create(
                        new Vec3(0.0D, 1.0D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D)),
                true, false, material);
        double length = connection.getLength();
        if (!(length > 0.0D) || length > MAX_CURVE_LENGTH) {
            return Result.implausibleLength(length);
        }
        fromEntity.addConnection(connection);
        toEntity.addConnection(connection.secondary());
        boolean attached = !fromEntity.getConnections().isEmpty() && !toEntity.getConnections().isEmpty();
        if (!attached) {
            return Result.notAttached(from, to);
        }
        DimBlend.LOGGER.info("Station depot curve {} -> {} attached, length {:.2f}", from, to, length);
        return Result.ok(length);
    }

    /**
     * Promotes a generator-written rail to a real track block entity. Idempotent: a rail
     * that already carries one is left alone. {@code setBlock} with {@code UPDATE_ALL}
     * runs {@code TrackBlock#onPlace}, which schedules the Create tick that registers the
     * rail in the track graph, so the promoted block is usable the same tick.
     */
    private static void ensureTrackBlockEntity(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof TrackBlockEntity) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(TrackBlock.HAS_BE) || state.getValue(TrackBlock.HAS_BE)) {
            return;
        }
        level.setBlock(pos, state.setValue(TrackBlock.HAS_BE, true), Block.UPDATE_ALL);
    }

    /** Curve start for a block: centre, half a block along the tangent, rail floor. */
    static Vec3 curveStart(BlockPos block, double[] tangent) {        return new Vec3(block.getX() + 0.5D + tangent[0] * 0.5D,
                block.getY(),
                block.getZ() + 0.5D + tangent[2] * 0.5D);
    }

    /**
     * The material of the track that is actually there, taken from the block itself.
     * Reading it beats guessing an id: the surface corridor uses Steam 'n' Rails' wide
     * birch track, and a curve whose material disagrees with its end blocks renders
     * wrong and can refuse to join the graph.
     */
    @javax.annotation.Nullable
    private static TrackMaterial materialOf(BlockState state) {
        if (state.getBlock() instanceof com.simibubi.create.content.trains.track.ITrackBlock track) {
            return track.getMaterial();
        }
        return null;
    }

    /** Outcome of one build attempt. */
    public record Result(Status status, double length, BlockPos missing, String detail) {
        public enum Status {
            OK,
            ALREADY_BUILT,
            MISSING_END,
            NO_MATERIAL,
            IMPLAUSIBLE_LENGTH,
            NOT_ATTACHED
        }

        static Result ok(double length) {
            return new Result(Status.OK, length, null, "");
        }

        static Result alreadyBuilt() {
            return new Result(Status.ALREADY_BUILT, 0.0D, null, "");
        }

        static Result missingEnd(BlockPos where) {
            return new Result(Status.MISSING_END, 0.0D, where, "no TrackBlockEntity at " + where);
        }

        static Result noMaterial(BlockPos where) {
            return new Result(Status.NO_MATERIAL, 0.0D, where, "no track block at " + where);
        }

        static Result implausibleLength(double length) {
            return new Result(Status.IMPLAUSIBLE_LENGTH, length, null,
                    "curve length " + length + " outside (0, " + MAX_CURVE_LENGTH + "]");
        }

        static Result notAttached(BlockPos from, BlockPos to) {
            return new Result(Status.NOT_ATTACHED, 0.0D, from,
                    "connection did not register at both ends " + from + " / " + to);
        }

        public boolean done() {
            return status == Status.OK || status == Status.ALREADY_BUILT;
        }

        /** True when retrying later could plausibly succeed. */
        public boolean retryable() {
            return status == Status.MISSING_END || status == Status.NOT_ATTACHED;
        }
    }
}