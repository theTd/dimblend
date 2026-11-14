package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.chassis.DepotCarQueryImpl.Car;
import dimblend.experience.chassis.DepotCarQueryImpl.Coupler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pure park-box rule behind {@link DepotCarQueryImpl}: no Minecraft world, no Sable sub-level,
 * only the graph and the boxes the implementation would have read out of them.
 */
class DepotCarQueryImplTest {
    private static final UUID POWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SPARE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SPARE2_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static final double SITE_X = 100.0D;
    private static final double SITE_Z = 40.0D;

    @Test
    void soleDepotCarAtItsSiteIsIdle() {
        Map<UUID, Car> component = Map.of(
                POWER_ID, depot(SITE_X, SITE_Z, List.of(free(), free())));
        assertTrue(DepotCarQueryImpl.parkedConsist(component, POWER_ID));
    }

    @Test
    void twoCoupledDepotCarsBothParkedAreIdle() {
        Map<UUID, Car> component = Map.of(
                SPARE_ID, depot(SITE_X, SITE_Z, List.of(linked(SPARE2_ID), free())),
                SPARE2_ID, depot(SITE_X + 10.0D, SITE_Z - 4.0D,
                        List.of(linked(SPARE_ID), free())));
        assertTrue(DepotCarQueryImpl.parkedConsist(component, SPARE_ID));
        // Both directions of the same consist must answer the same way.
        assertTrue(DepotCarQueryImpl.parkedConsist(component, SPARE2_ID));
    }

    @Test
    void soloDepotPowerCarDrivenAwayIsNotIdle() {
        Map<UUID, Car> component = Map.of(
                POWER_ID, depot(SITE_X + DepotCarQueryImpl.PARKED_MAX_DX + 1.0D, SITE_Z,
                        List.of(free())));
        assertFalse(DepotCarQueryImpl.parkedConsist(component, POWER_ID));
    }

    @Test
    void parkBoxToleratesDriftInsideButNotOutside() {
        assertEquals(32.0D, DepotCarQueryImpl.PARKED_MAX_DX);
        assertEquals(16.0D, DepotCarQueryImpl.PARKED_MAX_DZ);
        assertTrue(depot(SITE_X + 32.0D, SITE_Z + 16.0D, List.of(free())).parked());
        assertTrue(depot(SITE_X - 32.0D, SITE_Z - 16.0D, List.of(free())).parked());
        assertFalse(depot(SITE_X, SITE_Z + 16.5D, List.of(free())).parked());
        assertFalse(depot(SITE_X - 32.5D, SITE_Z, List.of(free())).parked());
    }

    @Test
    void parkedDepotCarCoupledToPlayerCarIsNotIdle() {
        Map<UUID, Car> component = Map.of(
                SPARE_ID, depot(SITE_X, SITE_Z, List.of(linked(PLAYER_ID))),
                PLAYER_ID, player(SITE_X + 8.0D, SITE_Z, List.of(linked(SPARE_ID))));
        assertFalse(DepotCarQueryImpl.parkedConsist(component, SPARE_ID));
    }

    @Test
    void parkedDepotCarCoupledToDepotCarThatLeftItsBoxIsNotIdle() {
        Map<UUID, Car> component = Map.of(
                SPARE_ID, depot(SITE_X, SITE_Z, List.of(linked(SPARE2_ID))),
                SPARE2_ID, depot(SITE_X, SITE_Z - 900.0D, List.of(linked(SPARE_ID))));
        assertFalse(DepotCarQueryImpl.parkedConsist(component, SPARE_ID));
    }

    @Test
    void unresolvedCouplerRefusesTheConsist() {
        Map<UUID, Car> unresolved = Map.of(
                SPARE_ID, depot(SITE_X, SITE_Z, List.of(unlinked())));
        assertFalse(DepotCarQueryImpl.parkedConsist(unresolved, SPARE_ID));
    }

    @Test
    void missingOrRemovedPartnerRefusesTheConsist() {
        // The partner id is known but the container no longer holds it (removed / unloaded).
        Map<UUID, Car> dangling = new java.util.HashMap<>();
        dangling.put(SPARE_ID, depot(SITE_X, SITE_Z, List.of(linked(SPARE2_ID))));
        dangling.put(SPARE2_ID, null);
        assertFalse(DepotCarQueryImpl.parkedConsist(dangling, SPARE_ID));

        // A partner id nobody described at all is the same dead link, not "uncoupled".
        Map<UUID, Car> unknown = Map.of(
                SPARE_ID, depot(SITE_X, SITE_Z, List.of(linked(PLAYER_ID))));
        assertFalse(DepotCarQueryImpl.parkedConsist(unknown, SPARE_ID));
    }

    @Test
    void cyclicComponentTerminatesAndStaysIdle() {
        Map<UUID, Car> cycle = Map.of(
                SPARE_ID, depot(SITE_X, SITE_Z, List.of(linked(SPARE2_ID))),
                SPARE2_ID, depot(SITE_X, SITE_Z, List.of(linked(SPARE_ID))));
        assertTrue(DepotCarQueryImpl.parkedConsist(cycle, SPARE_ID));
        assertTrue(DepotCarQueryImpl.parkedConsist(cycle, SPARE2_ID));

        // A self-link is the degenerate cycle.
        Map<UUID, Car> self = Map.of(
                SPARE_ID, depot(SITE_X, SITE_Z, List.of(linked(SPARE_ID))));
        assertTrue(DepotCarQueryImpl.parkedConsist(self, SPARE_ID));
    }

    @Test
    void unknownStartIsNotIdle() {
        assertFalse(DepotCarQueryImpl.parkedConsist(Map.of(), SPARE_ID));
        assertFalse(DepotCarQueryImpl.parkedConsist(
                Map.of(POWER_ID, depot(SITE_X, SITE_Z, List.of(free()))), SPARE_ID));
    }

    private static Car depot(double centerX, double centerZ, List<Coupler> couplers) {
        return new Car(centerX, centerZ, true, SITE_X, SITE_Z, couplers);
    }

    private static Car player(double centerX, double centerZ, List<Coupler> couplers) {
        return new Car(centerX, centerZ, false, 0.0D, 0.0D, couplers);
    }

    private static Coupler free() {
        return new Coupler(null, false);
    }

    private static Coupler linked(UUID partner) {
        return new Coupler(partner, false);
    }

    private static Coupler unlinked() {
        return new Coupler(null, true);
    }
}