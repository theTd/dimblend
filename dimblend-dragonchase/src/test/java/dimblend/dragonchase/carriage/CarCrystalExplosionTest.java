package dimblend.dragonchase.carriage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.dragonchase.structure.CarKind;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the two halves of a car crystal's explosion that need no level: the calculator that makes it
 * knockback only, and the recognition that says which crystals are the car's.
 *
 * <p>The calculator's three answers are checked with {@code null} arguments on purpose — the overrides
 * ignore their parameters, and what matters is that they never fall back to the vanilla
 * {@code true} / {@code 1.0F}.
 */
class CarCrystalExplosionTest {

    private static final UUID CAR = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SLOT_A = UUID.fromString("00000000-0000-0000-0000-0000000001a1");
    private static final UUID SLOT_B = UUID.fromString("00000000-0000-0000-0000-0000000001b1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000001c1");

    @Test
    void theReplacementBlastDamagesNothing() {
        assertFalse(CarCrystalExplosion.KNOCKBACK_ONLY.shouldDamageEntity(null, null),
                "no entity may be hurt: the player, the neighbouring crystals and the dragon all skip damage");
    }

    @Test
    void theReplacementBlastBreaksNoBlocks() {
        assertFalse(CarCrystalExplosion.KNOCKBACK_ONLY.shouldBlockExplode(null, null, null, null, 6.0F),
                "the deck, the campfire and the car's shell stay intact");
    }

    @Test
    void theKnockbackStaysAtTheVanillaAmount() {
        assertEquals(1.0F, CarCrystalExplosion.KNOCKBACK_ONLY.getKnockbackMultiplier(null),
                "the 6.0F shove is the danger the melee hit keeps");
    }

    @Test
    void theSpawnTagIdentifiesACarCrystal() {
        assertTrue(CarCrystalExplosion.hasCarTag(Set.of(CarCrystalExplosion.CRYSTAL_TAG)),
                "a crystal planted by the assembler carries the persisted tag");
        assertTrue(CarCrystalExplosion.hasCarTag(Set.of("keep_me", CarCrystalExplosion.CRYSTAL_TAG)),
                "other tags do not hide it");
        assertFalse(CarCrystalExplosion.hasCarTag(Set.of()), "a player-placed crystal has no tags");
        assertFalse(CarCrystalExplosion.hasCarTag(Set.of("sable_plot")), "a foreign tag is not ours");
    }

    @Test
    void aRegisteredCrystalCarOwnsItsSlotsAndNothingElse() {
        CarRegistry registry = new CarRegistry();
        CarRegistry.Car car = registry.register(CAR, CarKind.CRYSTAL);
        car.crystals.add(new CarRegistry.Crystal(SLOT_A, 0, 2, 1));
        car.crystals.add(new CarRegistry.Crystal(SLOT_B, 0, 2, -1));

        assertTrue(registry.ownsCrystal(SLOT_A),
                "an untagged crystal from an old save is still recognised through its record");
        assertTrue(registry.ownsCrystal(SLOT_B));
        assertFalse(registry.ownsCrystal(STRANGER), "a crystal nobody registered is not a car crystal");
        assertFalse(registry.ownsCrystal(null), "no id is never ours");
    }

    @Test
    void aRemovedCarsCrystalsAreNoLongerRecognised() {
        CarRegistry registry = new CarRegistry();
        CarRegistry.Car car = registry.register(CAR, CarKind.CRYSTAL);
        car.crystals.add(new CarRegistry.Crystal(SLOT_A, 0, 2, 0));
        car.removed = true;

        assertFalse(registry.ownsCrystal(SLOT_A),
                "a removed car's slots are cleared or orphaned, so they stop counting");
    }

    @Test
    void aBrakeOrNestCarOwnsNoCrystals() {
        CarRegistry registry = new CarRegistry();
        registry.register(CAR, CarKind.BRAKE);
        registry.register(SLOT_A, CarKind.NEST);

        assertFalse(registry.ownsCrystal(SLOT_B), "only crystal cars plant crystals");
    }
}
