package dimblend.dragonchase.carriage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.dragonchase.structure.CarKind;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure car rules: the 2 m/s threshold (metres per second, not blocks per tick), the 45°
 * fall-away launch that keeps X, the two exclusions of the chewing rule, and the registry's pruning
 * and persistence.
 */
class CarRulesTest {

    private static final String BOGEY = "simurail:physics_bogey";
    private static final String COUPLER = "simurail:automatic_coupler";
    private static final String COPYCAT_BEAM = "dimblend_blocks:creative_copycat_beam";

    private static final UUID LIVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REMOVED_STILL_LOADED = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID REMOVED_AND_GONE = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void thePushedThresholdIsTwoMetresPerSecondAndStrictlyAbove() {
        assertFalse(CarRules.isPushed(0.0));
        assertFalse(CarRules.isPushed(1.99));
        assertFalse(CarRules.isPushed(CarRules.PUSH_SPEED_MPS), "exactly 2 m/s is still rolling");
        assertTrue(CarRules.isPushed(CarRules.PUSH_SPEED_MPS + 0.001));
        assertEquals(2.0, CarRules.PUSH_SPEED_MPS);
    }

    @Test
    void theLaunchKeepsXAndSplitsTheRestAtFortyFiveDegrees() {
        double[] velocity = CarRules.launchVelocity(3.5, 1);
        assertEquals(3.5, velocity[0], "the car keeps its forward speed");
        double component = CarRules.LAUNCH_SPEED_MPS * Math.sin(Math.PI / 4.0);
        assertEquals(component, velocity[1], 1.0E-9);
        assertEquals(component, velocity[2], 1.0E-9);
        assertEquals(Math.abs(velocity[1]), Math.abs(velocity[2]), 1.0E-9,
                "45 degrees: the two components are equal");
    }

    @Test
    void theLaunchPicksTheRequestedSide() {
        assertEquals(-CarRules.LAUNCH_SPEED_MPS * Math.cos(Math.PI / 4.0), CarRules.launchVelocity(0.0, -1)[2], 1.0E-9);
        assertEquals(CarRules.LAUNCH_SPEED_MPS * Math.cos(Math.PI / 4.0), CarRules.launchVelocity(0.0, 1)[2], 1.0E-9);
    }

    @Test
    void unbreakableBlocksAreNeverChewed() {
        assertFalse(CarRules.chewable(-1.0F, "minecraft:purpur_block"),
                "hardness -1 is the creative copycats' unbreakable setting");
        assertFalse(CarRules.chewable(-1.0F, COPYCAT_BEAM));
    }

    @Test
    void ordinaryBlocksAreChewed() {
        assertTrue(CarRules.chewable(0.0F, "minecraft:redstone_block"));
        assertTrue(CarRules.chewable(1.5F, "minecraft:iron_block"), "no tool requirement changes the answer");
    }

    @Test
    void theBogeyAndTheCouplersAreNeverChewed() {
        assertFalse(CarRules.chewable(1.5F, BOGEY), "the bogey keeps the car on the rails");
        assertFalse(CarRules.chewable(1.5F, COUPLER), "the coupler is how it would have held on");
    }

    @Test
    void theTimingsMatchTheRequirement() {
        assertEquals(160, CarRules.SMOKE_TICKS, "8 seconds of black smoke");
        assertEquals(10, CarRules.DISSOLVE_TICKS, "the reward wagon's half second");
        assertEquals(4, CarRules.FALL_ARM_TICKS, "0.2 s before the launch");
        assertEquals(20, CarRules.FALL_FLIGHT_TICKS, "about a second of flight");
        assertEquals(4, CarRules.CHEW_INTERVAL_TICKS);
    }

    // ------------------------------------------------------------------ registry

    @Test
    void pruningKeepsLiveCarsAndRemovedCarsTheContainerStillHolds() {
        CarRegistry registry = newRegistry();

        assertTrue(registry.pruneRemoved(id -> !id.equals(REMOVED_AND_GONE)),
                "one record was dropped, so the data must be saved");
        assertNotNull(registry.find(LIVE), "a car that was never removed is never pruned");
        assertNotNull(registry.find(REMOVED_STILL_LOADED),
                "a removed car still in the container may still be walked by this tick's snapshot");
        assertNull(registry.find(REMOVED_AND_GONE), "a removed car the container forgot is unreachable");
        assertFalse(registry.pruneRemoved(id -> true), "nothing left to prune on the second pass");
    }

    @Test
    void theRegistryRoundTripsEveryPersistedFieldThroughNbt() {
        CarRegistry registry = newRegistry();
        CarRegistry.Car crystalCar = registry.find(REMOVED_STILL_LOADED);
        crystalCar.fallPhase = CarRules.FALL_FLYING;
        crystalCar.fallSide = -1;
        crystalCar.phaseDeadline = 9876L;
        crystalCar.smokeUntil = 4321L;
        crystalCar.dissolvePhase = CarRules.DISSOLVING;
        crystalCar.rewarded = true;
        crystalCar.retired = true;
        UUID crystalId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        crystalCar.crystals.add(new CarRegistry.Crystal(crystalId, 5, 2, -3));

        CarRegistry restored = CarRegistry.load(registry.save(new CompoundTag(), null));

        CarRegistry.Car back = restored.find(REMOVED_STILL_LOADED);
        assertNotNull(back);
        assertEquals(CarKind.CRYSTAL, back.kind);
        assertTrue(back.removed);
        assertEquals(CarRules.FALL_FLYING, back.fallPhase);
        assertEquals(-1, back.fallSide);
        assertEquals(9876L, back.phaseDeadline);
        assertEquals(4321L, back.smokeUntil);
        assertEquals(CarRules.DISSOLVING, back.dissolvePhase);
        assertTrue(back.rewarded);
        assertTrue(back.retired);
        assertEquals(1, back.crystals.size());
        CarRegistry.Crystal crystal = back.crystals.get(0);
        assertEquals(crystalId, crystal.id());
        assertEquals(5, crystal.dx());
        assertEquals(2, crystal.dy());
        assertEquals(-3, crystal.dz());
        assertNotNull(restored.find(LIVE));
        assertNotNull(restored.find(REMOVED_AND_GONE));
    }

    @Test
    void retiringCrystalHealingMarksOnlyLiveCrystalCarsAndIsIdempotent() {
        CarRegistry registry = new CarRegistry();
        UUID liveCrystal = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        UUID removedCrystal = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
        UUID liveBrake = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

        CarRegistry.Car c1 = registry.register(liveCrystal, CarKind.CRYSTAL);
        c1.crystals.add(new CarRegistry.Crystal(UUID.fromString("00000000-0000-0000-0000-0000000000e1"), 0, 2, 1));
        CarRegistry.Car c2 = registry.register(removedCrystal, CarKind.CRYSTAL);
        c2.removed = true;
        CarRegistry.Car b1 = registry.register(liveBrake, CarKind.BRAKE);

        assertTrue(registry.retireCrystalHealing());
        assertTrue(c1.retired, "live crystal car from finished chase must be retired from healing");
        assertEquals(1, c1.crystals.size(), "local crystal slot stays intact for loaded fall-away/glue");
        assertFalse(c2.retired, "removed crystal car does not flip retired");
        assertFalse(b1.retired, "brake car is unaffected");
        assertFalse(registry.retireCrystalHealing(), "second pass has nothing new to retire");
    }

    /** A registry with one live car, one removed car still loaded, and one removed and forgotten. */
    private static CarRegistry newRegistry() {
        CarRegistry registry = new CarRegistry();
        registry.register(LIVE, CarKind.NEST);
        registry.register(REMOVED_STILL_LOADED, CarKind.CRYSTAL).removed = true;
        registry.register(REMOVED_AND_GONE, CarKind.BRAKE).removed = true;
        return registry;
    }
}
