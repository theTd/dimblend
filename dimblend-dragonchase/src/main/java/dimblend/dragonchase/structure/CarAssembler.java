package dimblend.dragonchase.structure;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.carriage.CarRegistry;
import dimblend.dragonchase.carriage.DragonCarriages;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Writes the blocks of one car and turns them into a Sable sub-level.
 *
 * <p><b>The blocks are written here, not by worldgen.</b> A car needs block entities with
 * configured data — a bogey's options, copycat materials — and Sable assembly, both of which belong
 * on the server thread with players present. This is the shape the reward wagon and the station
 * depot's chassis use; the differences are the layouts ({@link CarPlans}) and the rules.
 *
 * <p><b>The site must already be loaded.</b> {@link #footprintReady} answers from
 * {@code ServerChunkCache#getChunkNow}, which never loads or generates: the caller (the director)
 * asks first and comes back on a later tick. Nothing here reads a chunk that may not be in memory.
 *
 * <p><b>Rollback on failure.</b> Every write is remembered; if anything throws, the blocks this
 * build placed are removed again so a retry starts from a clean track.
 *
 * <p><b>Force-loading is a per-car decision.</b> The nest is force-loaded (requirements.md §3.3,
 * the same ticket the station depot uses); the brake and crystal cars are not (§3.5), so simply not
 * adding a ticket is the whole implementation for them.
 */
public final class CarAssembler {

    /** Rails are at y = 64 in the corridor; the deck sits one above them, as on the reward wagon. */
    public static final int TRACK_Y = 64;
    public static final int DECK_Y = TRACK_Y + 1;

    private CarAssembler() {
    }

    /** The centre block of a car, from its centre X on the corridor line. */
    public static BlockPos center(int centerX) {
        return new BlockPos(centerX, DECK_Y, 0);
    }

    /**
     * True when every chunk the car at {@code centerX} touches is already a loaded full chunk.
     *
     * <p>{@code getChunkNow} is the non-blocking question: it returns {@code null} for a chunk that
     * is not in memory and never starts a load, which is what keeps the spawn rules off the
     * worldgen thread's back (dimblend-dev §8.28).
     *
     * <p><b>One block of margin.</b> The build writes with {@code Block.UPDATE_ALL}, which updates
     * the six face neighbours too. A car that ends exactly on a chunk border ({@code (centerX +
     * halfLength) % 16 == 15} or {@code (centerX - halfLength) % 16 == 0}) would therefore touch the
     * block in the next chunk over, and that chunk being absent is a synchronous load on the server
     * thread (dimblend-dev §8.28). The footprint is inflated by one block so the neighbour is
     * already loaded before the first write.
     */
    public static boolean footprintReady(ServerLevel level, CarKind kind, int centerX) {
        CarPlans.Plan plan = CarPlans.of(kind);
        int minChunkX = (centerX - plan.halfLength() - 1) >> 4;
        int maxChunkX = (centerX + plan.halfLength() + 1) >> 4;
        int minChunkZ = (-plan.halfWidth() - 1) >> 4;
        int maxChunkZ = (plan.halfWidth() + 1) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Re-asserts the nest's force-load ticket.
     *
     * <p>The nest is force-loaded for the whole chase (requirements.md §3.3). A ticket lives with the
     * sub-level's runtime entry, so after a world reload the director calls this again rather than
     * assuming the previous one survived; adding it twice is harmless (the container keeps one entry
     * per sub-level and ticket type).
     */
    public static void ensureNestTicket(ServerLevel level, @Nullable ServerSubLevel nest) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && nest != null && !nest.isRemoved()) {
            container.addForceLoadTicket(nest, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        }
    }

    /**
     * Builds and assembles one car.
     *
     * @param initialVx metres per second along +X given to the body after assembly; 0 for the nest
     * @return the sub-level, or {@code null} after rolling back every placed block
     */
    @Nullable
    public static ServerSubLevel assemble(ServerLevel level, CarKind kind, int centerX, double initialVx) {
        CarPlans.Plan plan = CarPlans.of(kind);
        BlockPos center = center(centerX);
        if (!footprintReady(level, kind, centerX)) {
            // The caller may have skipped its own check. Writing blocks here would make the server
            // synchronously load (and generate) the site on the server thread (dimblend-dev §8.28),
            // so a not-yet-loaded site is simply "not this tick": the caller retries.
            DimBlendDragonChase.LOGGER.debug("Dragon Chase {} at X={} deferred: chunks not loaded", kind, centerX);
            return null;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            DimBlendDragonChase.LOGGER.warn("No Sable sub-level container in {}; {} not built",
                    level.dimension().location(), kind);
            return null;
        }
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        try {
            for (CarPlans.Cell cell : plan.parts()) {
                BlockState state = CarBlocks.stateFor(cell);
                if (state == null) {
                    continue;
                }
                BlockPos pos = center.offset(cell.dx(), cell.dy(), cell.dz());
                level.setBlock(pos, state, Block.UPDATE_ALL);
                placed.put(pos.immutable(), state);
                // Block entities now exist; the copycat materials and bogey options live in them.
                CarBlocks.dress(level, pos, cell);
            }
            BoundingBox3i bounds = new BoundingBox3i(
                    center.getX() - plan.halfLength(), center.getY(), center.getZ() - plan.halfWidth(),
                    center.getX() + plan.halfLength(), center.getY() + plan.topDy(),
                    center.getZ() + plan.halfWidth());
            ServerSubLevel car = SubLevelAssemblyHelper.assembleBlocks(level, center, placed.keySet(), bounds);
            if (car == null || car.isRemoved()) {
                rollback(level, placed);
                return null;
            }
            // SubLevelAssemblyHelper#assembleBlocks writes logicalPose + lastPose without filling
            // SubLevel#globalBounds (which stays [0,0,0 -> 0,0,0] until Sable's next tick). Refresh
            // globalBounds + lastGlobalBounds now so same-tick director/marker/car-tick checks never
            // read centerX == 0.0 (which would match Region-0 depot cars within 128 blocks!).
            car.updateBoundingBox();
            car.forceUpdateGlobalBounds();
            // Sable's plot has its own light engine and assembly never fills it; without this the
            // car renders and reloads fully black (see PlotRelight).
            PlotRelight.relight(level, car);
            if (kind == CarKind.NEST) {
                // requirements.md §3.3: the nest is force-loaded. The brake and crystal cars are
                // deliberately not (§3.5), so simply no ticket for them.
                ensureNestTicket(level, car);
            }
            DragonCarriages.register(level, car, kind);
            if (initialVx != 0.0 && !CarMotion.setVelocity(car, initialVx, 0.0, 0.0)) {
                CarMotion.warnMissingHandle(car);
            }
            return car;
        } catch (RuntimeException failure) {
            DimBlendDragonChase.LOGGER.warn("Dragon Chase {} at X={} failed: {}", kind, centerX, failure.toString());
            rollback(level, placed);
            return null;
        }
    }

    /**
     * Puts back exactly the blocks this build wrote, and nothing else.
     *
     * <p>A block is only cleared when it is still the one this build placed, so a partly moved
     * structure (assembly writes into the plot before it can fail) is never damaged by the retry.
     */
    private static void rollback(ServerLevel level, Map<BlockPos, BlockState> placed) {
        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            if (level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                level.setBlock(entry.getKey(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        placed.clear();
    }

    /**
     * Forces one neighbour update off the brake car's redstone block after assembly, so the bogey
     * sees its signal (and goes into braking) and the lamp above it lights.
     *
     * <p>The bogey reads the signal from the block above it every physics tick
     * ({@code PhysicsBogeyBlockEntity#getControlStrength}), so nothing needs caching; the lamp, by
     * contrast, only changes on a neighbour update, and moving blocks into a plot is a plain
     * chunk write that fires none.
     */
    public static void primeBrakeSignal(ServerLevel level, ServerSubLevel car) {
        CarPlans.Cell redstone = CarPlans.of(CarKind.BRAKE).redstone();
        if (redstone == null) {
            return;
        }
        BlockPos pos = plotPos(car, redstone);
        level.updateNeighborsAt(pos, Blocks.REDSTONE_BLOCK);
    }

    /** The plot position of one cell of a car (see {@link CarMotion#plotPos}). */
    public static BlockPos plotPos(ServerSubLevel car, CarPlans.Cell cell) {
        return CarMotion.plotPos(car, cell);
    }

    /**
     * Spawns the crystal car's 1-4 end crystals and records them for the runtime rules.
     *
     * <p><b>No bottom plate</b> ({@code EndCrystal#setShowBottom(false)}) — the requirement draws
     * bare crystals on the deck.
     *
     * <p><b>Why they are tracked by UUID.</b> Sable does <i>not</i> carry an ordinary entity with a
     * sub-level on the server: the stick-to-sub-level mechanism is fed by
     * {@code EntityStickExtension#sable$setPlotPosition}, whose only callers are the client packet
     * handler ({@code ClientPacketListenerMixin.java:88-101}) and the client lerp; the server-side
     * {@code EntityMixin#sable$updateSubLevelPosition} merely follows a plot position that was set
     * there. A freshly spawned crystal therefore stays where it was put while the car drives off, so
     * {@code CarTicker} re-glues every tracked crystal to its plot cell each tick.
     */
    public static void plantCrystals(ServerLevel level, ServerSubLevel car) {
        CarPlans.Plan plan = CarPlans.of(CarKind.CRYSTAL);
        List<CarPlans.Cell> ends = new ArrayList<>(plan.endCells());
        if (ends.isEmpty()) {
            DimBlendDragonChase.LOGGER.warn("Crystal car {} has no end-stone cells", car.getUniqueId());
            return;
        }
        RandomSource random = level.getRandom();
        Collections.shuffle(ends, new Random(random.nextLong()));
        int count = Math.min(ends.size(), 1 + random.nextInt(4));
        CarRegistry.Car record = CarRegistry.get(level).find(car.getUniqueId());
        for (int i = 0; i < count; i++) {
            CarPlans.Cell cell = ends.get(i);
            BlockPos plotPos = plotPos(car, cell).above();
            Vec3 world = CarMotion.worldOf(car, plotPos);
            EndCrystal crystal = new EndCrystal(level, world.x, world.y, world.z);
            crystal.setShowBottom(false);
            if (!level.addFreshEntity(crystal)) {
                DimBlendDragonChase.LOGGER.warn("Crystal car {} could not add a crystal at {}", car.getUniqueId(), plotPos);
                continue;
            }
            if (record != null) {
                record.crystals.add(new CarRegistry.Crystal(crystal.getUUID(),
                        cell.dx(), cell.dy() + 1, cell.dz()));
            }
        }
        if (record != null) {
            CarRegistry.get(level).setDirty();
        }
    }
}
