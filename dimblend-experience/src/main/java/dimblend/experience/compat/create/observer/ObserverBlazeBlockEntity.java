package dimblend.experience.compat.create.observer;

import dimblend.experience.headlight.MarkedLavaTracker;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSettings;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Holds the burner this block replaced -- its block state and its full block entity NBT, so fuel, burn time,
 * goggles and the creative flag all come back on exit -- and drives the marked-lava query.
 *
 * <p>Server tick: every {@value #QUERY_TICKS} ticks ask the level's {@link MarkedLavaTracker} whether marked lava
 * lies within the tuned detection radius of this block's projected world centre, then write POWERED with a
 * neighbour-notifying update. Client tick: nothing here; the head chase and the particles' continuous stream live
 * in {@code ObserverBlazeRenderer} and the block's {@code animateTick}.</p>
 */
public final class ObserverBlazeBlockEntity extends BlockEntity {
    private static final int QUERY_TICKS = MarkedLavaTracker.RECOMPUTE_TICKS;
    private static final String STORED_STATE = "OriginalState";
    private static final String STORED_DATA = "OriginalData";
    private static final float DEFAULT_HEAD_ANGLE = 90.0F;
    /** Ticks until the next query; the first one runs on the tick after placement, then every query interval. */
    private int ticksUntilQuery = 1;

    @Nullable
    private BlockState originalState;
    @Nullable
    private CompoundTag originalData;
    private boolean goggles;
    private float headAngleDegrees = DEFAULT_HEAD_ANGLE;

    public ObserverBlazeBlockEntity(BlockPos pos, BlockState state) {
        super(ObserverBlazeBlocks.OBSERVER_BLAZE_ENTITY.get(), pos, state);
    }

    /** The burner block state this observer replaced; null before a swap and on clients that only got the update tag. */
    @Nullable
    public BlockState originalState() {
        return originalState;
    }

    /** The replaced burner's full block entity NBT; null under the same conditions as {@link #originalState()}. */
    @Nullable
    public CompoundTag originalData() {
        return originalData;
    }

    public boolean goggles() {
        return goggles;
    }

    public float headAngleDegrees() {
        return headAngleDegrees;
    }

    /** Client-side head chaser: turn towards {@code targetDegrees} along the shortest arc. */
    public void chaseHeadAngle(float targetDegrees, float factor) {
        headAngleDegrees = Mth.wrapDegrees(headAngleDegrees + Mth.wrapDegrees(targetDegrees - headAngleDegrees) * factor);
    }

    /** Called by the swap on the server: keeps everything the burner needs to come back exactly. */
    public void storeReplacedBurner(BlockState state, CompoundTag data) {
        originalState = state;
        originalData = data.copy();
        goggles = data.getBoolean("Goggles");
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    public void tickObserver(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server)) {
            // animateTick is a sampled hook; the continuous flame stream comes from the ticker the block owns.
            if (state.getValue(ObserverBlazeBlock.POWERED) && level.getRandom().nextInt(2) == 0) {
                ObserverBlazeBlock.emitFlame(level, pos, level.getRandom(), 1);
            }
            return;
        }
        if (--ticksUntilQuery > 0) {
            return;
        }
        ticksUntilQuery = QUERY_TICKS;
        Vec3 center = MarkedLavaTracker.worldCenter(server, pos);
        double radius = TuningSettings.get(TuningOption.OBSERVER_BLAZE_RADIUS);
        boolean marked = MarkedLavaTracker.of(server).markedNear(server, pos, center, radius);
        if (state.getValue(ObserverBlazeBlock.POWERED) != marked) {
            // Flag 3 notifies neighbours and clients: a redstone consumer reads the new level this tick.
            server.setBlock(pos, state.setValue(ObserverBlazeBlock.POWERED, marked), Block.UPDATE_ALL);
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel server) {
            MarkedLavaTracker.of(server).register(getBlockPos());
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            MarkedLavaTracker.of(server).unregister(getBlockPos());
        }
        super.setRemoved();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level instanceof ServerLevel server) {
            MarkedLavaTracker.of(server).register(getBlockPos());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (originalState != null) {
            tag.put(STORED_STATE, NbtUtils.writeBlockState(originalState));
        }
        if (originalData != null) {
            tag.put(STORED_DATA, originalData.copy());
        }
        if (goggles) {
            tag.putBoolean("Goggles", true);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        originalState = tag.contains(STORED_STATE)
                ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound(STORED_STATE))
                : null;
        originalData = tag.contains(STORED_DATA) ? tag.getCompound(STORED_DATA).copy() : null;
        goggles = tag.getBoolean("Goggles");
    }

    /**
     * Client sync is only what the renderer needs: the goggles flag (the rest of the replaced burner never leaves
     * the server) and nothing else -- POWERED travels as a block state.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (goggles) {
            tag.putBoolean("Goggles", true);
        }
        return tag;
    }
}
