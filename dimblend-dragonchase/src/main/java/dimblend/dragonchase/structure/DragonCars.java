package dimblend.dragonchase.structure;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.carriage.CarRegistry;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Building the three structures on the corridor track (rails at y = 64, corridor line z = 0, long
 * axis along X, couplers at both X ends) and turning them into Sable sub-levels. Server-thread only.
 *
 * <p>The director (W5) decides when and where; it always checks {@link #footprintReady} first,
 * so a build never loads or generates chunks.
 *
 * <p><b>Where the work lives.</b> The drawings are {@link CarPlans}, the block ids and block-entity
 * dressing {@link CarBlocks}, the assembly (with rollback) {@link CarAssembler}, the kill rewards
 * {@link NestReward}; this class is the three public entry points plus the two one-shot side effects
 * that belong to a build (the brake signal priming and the reward partition).
 */
public final class DragonCars {

    /** True when every chunk the structure at {@code centerX} touches is a loaded FULL chunk ({@code getChunkNow}). */
    public static boolean footprintReady(ServerLevel level, CarKind kind, int centerX) {
        return CarAssembler.footprintReady(level, kind, centerX);
    }

    /**
     * Builds the nest (§3.3 layout: 11 long × 7 wide × 5 layers; reward cells left empty), adds a
     * Sable force-load ticket and registers it with {@code DragonCarriages}.
     *
     * @return the sub-level, or null after rolling back every placed block
     */
    @Nullable
    public static ServerSubLevel buildNest(ServerLevel level, int centerX) {
        return CarAssembler.assemble(level, CarKind.NEST, centerX, 0.0);
    }

    /**
     * Re-asserts the nest's force-load ticket (requirements.md §3.3).
     *
     * <p>Called by the director after a world reload: the nest must stay loaded for the whole chase,
     * and a reloaded sub-level starts without its ticket. Safe to call every tick and on a nest that
     * is already gone (it does nothing then).
     */
    public static void ensureNestTicket(ServerLevel level, ServerSubLevel nest) {
        CarAssembler.ensureNestTicket(level, nest);
    }

    /**
     * Builds a brake car (§3.5: 13 long × 7 wide; redstone block at bogie y+1, lamp at y+2,
     * powered once after assembly so the bogie brakes and the lamp lights), no force-load ticket,
     * then gives it {@code initialVx} m/s along +X. Registers it with {@code DragonCarriages}.
     */
    @Nullable
    public static ServerSubLevel buildBrakeCar(ServerLevel level, int centerX, double initialVx) {
        ServerSubLevel car = CarAssembler.assemble(level, CarKind.BRAKE, centerX, initialVx);
        if (car == null) {
            return null;
        }
        CarAssembler.primeBrakeSignal(level, car);
        return car;
    }

    /**
     * Builds a crystal car (§3.5: 13 long × 7 wide; 1-4 end crystals on random end-stone-brick
     * cells at y+1), no force-load ticket, then gives it {@code initialVx} m/s along +X.
     * Registers it with {@code DragonCarriages}.
     */
    @Nullable
    public static ServerSubLevel buildCrystalCar(ServerLevel level, int centerX, double initialVx) {
        ServerSubLevel car = CarAssembler.assemble(level, CarKind.CRYSTAL, centerX, initialVx);
        if (car == null) {
            return null;
        }
        CarAssembler.plantCrystals(level, car);
        return car;
    }

    /**
     * After the kill (§3.6, decision reward-blocks): storage blocks into the 20 ore cells
     * (copper 2, iron 8, zinc 5, gold 2, diamond 2, netherite 1, shuffled by {@code seed}) and a
     * shulker box with the 27-slot list into the chest cell; then starts the end-portal particle
     * hint at the chest cell's y-1.
     *
     * <p>Idempotent per nest: a second call is ignored, so a repeated kill handler cannot hand out a
     * second set of blocks. The portal hint itself is a per-tick rule in {@code CarTicker}, driven
     * by the same flag.
     */
    public static void placeNestRewards(ServerLevel level, ServerSubLevel nest, long seed) {
        CarRegistry registry = CarRegistry.get(level);
        CarRegistry.Car record = registry.find(nest.getUniqueId());
        if (record == null) {
            // Not one of ours; writing blocks into a stranger's plot would be worse than skipping.
            DimBlendDragonChase.LOGGER.warn("Nest rewards skipped: {} is not a registered Dragon Chase car",
                    nest.getUniqueId());
            return;
        }
        if (record.rewarded) {
            return;
        }
        NestReward.place(level, nest, seed);
        record.rewarded = true;
        registry.setDirty();
    }

    private DragonCars() {
    }
}
