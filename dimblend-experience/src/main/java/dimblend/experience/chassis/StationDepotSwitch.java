package dimblend.experience.chassis;

import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dimblend.api.StationDepotCurves;
import dimblend.api.StationDepotSites;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The depot's single andesite track switch (安山岩转辙机), standing beside the junction
 * instead of in the running line.
 *
 * <p><b>Where the machine stands, and why not above the rails.</b> The first version put
 * the block one block above the junction column, and the user reported on 2026-11-01 that
 * it stopped the car. That is inherent to the block: {@code CRShapes.ANDESITE_SWITCH}
 * fills the whole 16x16 footprint at 0..5px and adds a post to 15px, and
 * {@code TrackSwitchBlock#getCollisionShape} returns that shape whenever the block's
 * {@code hasCollision} is set — which it is, because {@code CRBlocks} registers the switch
 * with {@code noOcclusion()} but never {@code noCollission()}. A switch machine therefore
 * has to stand where no vehicle sweeps, which on this depot means at least
 * {@link ChassisBlueprint#SAFE_Z}+1 blocks off the main-line centre.
 *
 * <p><b>Which junction it serves.</b> The depot has two merge columns one block apart
 * ({@link StationDepotCurves#POSITIVE_MERGE_DX} and
 * {@link StationDepotCurves#NEGATIVE_MERGE_DX}); the positive one carries the pre-built
 * chassis. A Railways switch disables every exit but the selected one
 * ({@code TrackSwitch#updateEdges}: {@code setEnabled(forceActive || getSwitchTarget() == to)}),
 * so the machine works the <b>positive</b> merge: its default straight state is the main
 * line, and the carriage siding's branch stays closed until a player throws the lever. That
 * is the user's choice of 2026-11-02 — a switch is wanted at the junction the car uses, and
 * a machine that only works the empty siding was the wrong one.
 *
 * <p><b>Why the block entity's target is an offset, not a position.</b>
 * {@code TrackTargetingBehaviour#determineGraphLocation} works from
 * {@code targetTrack.offset(blockEntity.getBlockPos())} (Create 6.0.10,
 * {@code TrackTargetingBehaviour.java:314}), so the machine may stand anywhere as long as
 * its target points at a rail block. That is what lets the block move off the rails
 * without losing the junction.
 *
 * <p><b>No Railways compile dependency.</b> The switch is resolved by registry id and its
 * behaviour is read through Create's own classes, so experience imports nothing from
 * Railways for this feature.
 */
public final class StationDepotSwitch {
    /** Railways' manual andesite switch — the block the depot's inventory names. */
    public static final ResourceLocation SWITCH_ID =
            ResourceLocation.fromNamespaceAndPath("railways", "track_switch_andesite");

    /**
     * Lateral offset of the machine from the main-line centre, in blocks: on the siding's
     * side of the rails.
     *
     * <p>{@link ChassisBlueprint#SAFE_Z} is the chassis's own lateral clearance (3), so
     * {@code SAFE_Z + 1} keeps the block clear of any car on the main line. The user moved it
     * three blocks further on 2026-11-03 ({@link #MACHINE_DZ_SHIFT}), which lands it at z = 7:
     * still off the main line, and one block short of a carriage standing on the positive siding
     * (that car spans z = 8..12, since the siding is at z = 10 and the blueprint is five wide).
     * One block further would put the switch inside a car's footprint and the car would hit it —
     * the same defect the first version had when the machine stood above the rails.
     */
    static final int MACHINE_DZ_SHIFT = 3;
    static final int MACHINE_DZ = ChassisBlueprint.SAFE_Z + 1 + MACHINE_DZ_SHIFT;

    private StationDepotSwitch() {
    }

    /** Rail height: the machine stands on the depot floor at the same level as the rails. */
    private static int railY() {
        return StationDepotSites.CHASSIS_Y - 1;
    }

    /** The machine's own block. */
    public static BlockPos machine(int boundaryX) {
        return new BlockPos(boundaryX + StationDepotCurves.POSITIVE_MERGE_DX, railY(), MACHINE_DZ);
    }

    /** The rail block the switch belongs to: the carriage siding's merge column. */
    public static BlockPos target(int boundaryX) {
        return new BlockPos(boundaryX + StationDepotCurves.POSITIVE_MERGE_DX, railY(),
                StationDepotSites.POWER_CHASSIS_Z);
    }

    /**
     * Places the machine and points it at the merge column, or reports why it cannot.
     *
     * <p>Idempotent: a machine already standing at {@link #machine(int)} is inspected
     * instead of replaced, which is what turns a later visit into the verification step —
     * Create binds the edge point on the block entity's next tick, not inside {@code read}.
     */
    static Result fit(ServerLevel level, int boundaryX) {
        BlockPos machine = machine(boundaryX);
        BlockPos target = target(boundaryX);
        if (!(level.getBlockState(target).getBlock() instanceof ITrackBlock)) {
            return new Result(Kind.WAITING, "no rail at " + target + " yet");
        }
        Block switchBlock = BuiltInRegistries.BLOCK.getOptional(SWITCH_ID).orElse(null);
        if (switchBlock == null) {
            return new Result(Kind.NO_MOD, "missing block " + SWITCH_ID + "; the junction stays plain track");
        }
        BlockState existing = level.getBlockState(machine);
        if (existing.is(switchBlock)) {
            return inspect(level, machine);
        }
        if (!existing.canBeReplaced()) {
            return new Result(Kind.OCCUPIED, "the machine's spot " + machine + " is taken by " + existing);
        }
        // The switch binds only when Create's graph has a node at the far end of the edge
        // it sits on: TrackSwitchBlockEntity#calculateExits needs edge.node2, and
        // TrackTargetingBehaviour finds that node by walking the straight rail east of the
        // target. East of the carriage merge that rail already carries a block entity (the
        // negative curve is attached to the next column), so this is usually a no-op; it is
        // kept because a depot whose other curve failed would otherwise leave the machine
        // unable to bind for no visible reason.
        StationDepotCurveBuilder.ensureTrackBlockEntity(level, target.east());
        level.setBlock(machine, switchBlock.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        BlockEntity entity = level.getBlockEntity(machine);
        if (entity == null) {
            return new Result(Kind.WAITING, "the switch block entity did not appear at " + machine);
        }
        TrackTargetingBehaviour<?> behaviour = BlockEntityBehaviour.get(entity, TrackTargetingBehaviour.TYPE);
        if (behaviour == null) {
            return new Result(Kind.WAITING, "the switch block carries no track targeting behaviour");
        }
        // The same keys a saved switch carries: an id, the targeted rail as an offset from
        // this block, whether the target is a straight piece, and which way along the track
        // the switch looks. Positive looks west from the machine, so edge.node2 is the merge
        // node and the switch's remaining connections are the main line west and the
        // carriage siding's curve — the fork it is there to work.
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", UUID.randomUUID());
        tag.put("TargetTrack", NbtUtils.writeBlockPos(target.subtract(machine)));
        tag.putBoolean("Ortho", true);
        tag.putBoolean("TargetDirection", true);
        behaviour.read(tag, level.registryAccess(), false);
        entity.setChanged();
        if (entity instanceof SmartBlockEntity smart) {
            smart.sendData();
        }
        return new Result(Kind.PENDING, "machine placed at " + machine + ", targeting " + target);
    }

    /**
     * Reports whether Create has bound the switch to the track graph yet, and where.
     *
     * <p>Only Create's own {@link TrackEdgePoint} fields are read — no Railways class is
     * touched — so the log line can say which edge the switch landed on without a
     * compile-time dependency. A switch is useful exactly when its edge ends at the merge
     * node; one that never binds would otherwise leave the depot looking fitted while
     * nothing worked.
     */
    private static Result inspect(ServerLevel level, BlockPos machine) {
        if (!(level.getBlockEntity(machine) instanceof SmartBlockEntity smart)) {
            return new Result(Kind.WAITING, "the machine at " + machine + " has no block entity");
        }
        TrackTargetingBehaviour<?> behaviour = BlockEntityBehaviour.get(smart, TrackTargetingBehaviour.TYPE);
        if (behaviour == null) {
            return new Result(Kind.WAITING, "the machine at " + machine + " has no track targeting behaviour");
        }
        TrackEdgePoint point = behaviour.getEdgePoint();
        if (point == null) {
            return new Result(Kind.PENDING, "placed at " + machine + ", Create has not registered the edge yet");
        }
        if (point.edgeLocation == null) {
            return new Result(Kind.PENDING, "bound at " + machine + " without an edge location");
        }
        Vec3 first = point.edgeLocation.getFirst().getLocation();
        Vec3 second = point.edgeLocation.getSecond().getLocation();
        return new Result(Kind.FITTED, String.format(
                "machine at %s bound on edge (%.1f, %.1f, %.1f) -> (%.1f, %.1f, %.1f) at %.2f",
                machine, first.x, first.y, first.z, second.x, second.y, second.z, point.position));
    }

    /** What happened, in the order the caller logs it. */
    public enum Kind {
        /** Bound to Create's track graph: the switch is usable. */
        FITTED,
        /** Placed, but Create has not registered the edge yet; harmless and retried. */
        PENDING,
        /** A precondition is missing; retried on a later visit. */
        WAITING,
        /** Something else stands where the machine belongs; reported once. */
        OCCUPIED,
        /** Railways is not installed; the junction correctly stays plain track. */
        NO_MOD
    }

    /** What one fit attempt achieved. */
    public record Result(Kind kind, String detail) {
        /** True when the switch is in place and bound. */
        public boolean fitted() {
            return kind == Kind.FITTED;
        }

        /** True when retrying cannot help until something else changes. */
        public boolean done() {
            return kind == Kind.FITTED || kind == Kind.OCCUPIED || kind == Kind.NO_MOD;
        }
    }
}
