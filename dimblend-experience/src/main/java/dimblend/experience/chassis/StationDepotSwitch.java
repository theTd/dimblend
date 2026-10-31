package dimblend.experience.chassis;

import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackTargetingBehaviour;
import dimblend.api.StationDepotSites;
import dimblend.experience.DimBlend;
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

/**
 * Places the depot's single andesite track switch (安山岩转辙机) on the main line.
 *
 * <p><b>Why here and not in worldgen.</b> A switch is not a plain block: it carries a
 * block entity whose {@code TrackTargetingBehaviour} must name the track block it
 * belongs to and the direction it faces. That needs the server thread and the
 * dimension's registry access, neither of which a worker-thread decoration pass may
 * touch.
 *
 * <p><b>No Railways compile dependency.</b> The switch block is resolved by registry id
 * and its block entity is handled through Create's own
 * {@link TrackTargetingBehaviour} base class, so experience needs no import from
 * Railways for this feature. The block entity type is whatever the block registers.
 *
 * <p><b>How the target is set, using public API only.</b>
 * {@link TrackTargetingBehaviour#read(CompoundTag, net.minecraft.core.HolderLookup.Provider, boolean)}
 * is exactly the path a chunk load uses to restore a saved switch, so writing the same
 * keys by hand produces the same state a saved switch has. The behaviour then creates
 * its edge point on its own next tick, once Create's track graph contains the main
 * line; until then it retries harmlessly.
 */
public final class StationDepotSwitch {
    /** Railways' manual andesite switch — the block the picture names. */
    public static final ResourceLocation SWITCH_ID =
            ResourceLocation.fromNamespaceAndPath("railways", "track_switch_andesite");

    private StationDepotSwitch() {
    }

    /** Where the switch block stands: above the junction block of the main line. */
    public static BlockPos at(int boundaryX) {
        return new BlockPos(StationDepotSites.junctionX(boundaryX), StationDepotSites.controlY(),
                StationDepotSites.POWER_CHASSIS_Z);
    }

    /**
     * Places the switch at this boundary's junction, targeting the main-line track
     * directly below it.
     *
     * @param curvesAttached whether both merge curves are already attached. The switch's
     *     exits are the two curve ends, so it is only worth placing once they exist.
     */
    public static Result fit(ServerLevel level, int boundaryX, boolean curvesAttached) {
        BlockPos pos = at(boundaryX);
        BlockPos track = pos.below();
        if (!(level.getBlockState(track).getBlock() instanceof ITrackBlock)) {
            return Result.WAITING;
        }
        Block switchBlock = BuiltInRegistries.BLOCK.getOptional(SWITCH_ID).orElse(null);
        if (switchBlock == null) {
            // Missing mod or renamed block: leave the junction as plain track rather
            // than throwing inside a tick.
            DimBlend.LOGGER.warn("Missing switch block {}; the depot junction stays plain track", SWITCH_ID);
            return Result.NO_MOD;
        }
        if (!curvesAttached) {
            return Result.WAITING_FOR_CURVES;
        }
        BlockState existing = level.getBlockState(pos);
        if (existing.is(switchBlock)) {
            return Result.ALREADY_PLACED;
        }
        BlockState state = switchBlock.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) {
            return Result.WAITING;
        }
        TrackTargetingBehaviour<?> behaviour =
                com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(
                        entity, TrackTargetingBehaviour.TYPE);
        if (behaviour == null) {
            return Result.WAITING;
        }
        // Same keys a saved switch carries: an id, the targeted track block as an
        // offset from this block, the facing direction, and whether the target is a
        // straight (orthogonal) block — which the main line is.
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", java.util.UUID.randomUUID());
        tag.put("TargetTrack", NbtUtils.writeBlockPos(track.subtract(pos)));
        tag.putBoolean("Ortho", true);
        tag.putBoolean("TargetDirection", true);
        behaviour.read(tag, level.registryAccess(), false);
        entity.setChanged();
        if (entity instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) {
            smart.sendData();
        }
        // The edge point only materialises once Create's graph contains the main line.
        // Not being there yet is normal for a freshly decorated chunk, so it is checked
        // and reported rather than assumed.
        if (behaviour.getEdgePoint() == null) {
            DimBlend.LOGGER.warn("Station depot switch at {} placed but not yet bound: the main line is not in "
                    + "the track graph yet, will retry on the next visit", pos);
            return Result.PLACED_UNBOUND;
        }
        DimBlend.LOGGER.info("Station depot switch placed at {} targeting {}", pos, track);
        return Result.PLACED;
    }

    /** Outcome of one switch placement attempt. */
    public enum Result {
        /** Placed and bound to the track graph. */
        PLACED(true),
        /** Placed, but Create had not registered the main line yet; harmless, retried. */
        PLACED_UNBOUND(true),
        /** Already in place from an earlier attempt. */
        ALREADY_PLACED(true),
        /** The switch block does not exist; the junction correctly stays plain track. */
        NO_MOD(true),
        /** Both merge curves must exist before the switch's exits can bind. */
        WAITING_FOR_CURVES(false),
        /** No rail below the junction yet; retry on a later visit. */
        WAITING(false);

        private final boolean finished;

        Result(boolean finished) {
            this.finished = finished;
        }

        /** True when there is nothing left for this class to do for the boundary. */
        public boolean done() {
            return finished;
        }
    }
}