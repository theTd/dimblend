package dimblend.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link NonCrewCarriages}: the speed floor asks who is "not the player's train", and the answer
 * must be empty until the mod that owns those cars installs its provider (requirements §6.1). The
 * force-load protection is a second, independent hook: the region purge asks whether a chain may be
 * unticketed, and until a guard is installed the answer is "yes, unticket it".
 */
class NonCrewCarriagesTest {
    @AfterEach
    void dropProvider() {
        NonCrewCarriages.reset();
    }

    @Test
    void noProviderMeansNobody() {
        assertEquals(Set.of(), NonCrewCarriages.ids(null));
    }

    @Test
    void providerAnswersAndResetDropsIt() {
        UUID id = UUID.randomUUID();
        NonCrewCarriages.install(level -> Set.of(id));
        assertEquals(Set.of(id), NonCrewCarriages.ids(null));

        NonCrewCarriages.install(null);
        assertTrue(NonCrewCarriages.ids(null).isEmpty(), "install(null) removes the provider");
    }

    @Test
    void providerWithoutAnAnswerMeansNobody() {
        NonCrewCarriages.install(level -> null);
        assertEquals(Set.of(), NonCrewCarriages.ids(null));
    }

    @Test
    void nothingIsForceLoadProtectedWithoutAGuard() {
        assertFalse(NonCrewCarriages.isForceLoadProtected(null, UUID.randomUUID()),
                "a save without the owning mod purges exactly as before");
    }

    @Test
    void guardAnswersPerSubLevelAndResetDropsIt() {
        UUID nest = UUID.randomUUID();
        NonCrewCarriages.installUnticketGuard((level, subLevelId) -> subLevelId.equals(nest));
        assertTrue(NonCrewCarriages.isForceLoadProtected(null, nest));
        assertFalse(NonCrewCarriages.isForceLoadProtected(null, UUID.randomUUID()));

        NonCrewCarriages.installUnticketGuard(null);
        assertFalse(NonCrewCarriages.isForceLoadProtected(null, nest),
                "installUnticketGuard(null) removes the guard");
    }

    @Test
    void guardAndProviderAreIndependent() {
        UUID car = UUID.randomUUID();
        NonCrewCarriages.install(level -> Set.of(car));
        assertFalse(NonCrewCarriages.isForceLoadProtected(null, car),
                "being a non-crew car does not by itself protect the tickets");

        NonCrewCarriages.installUnticketGuard((level, subLevelId) -> subLevelId.equals(car));
        assertEquals(Set.of(car), NonCrewCarriages.ids(null), "the guard does not change the id set");

        NonCrewCarriages.reset();
        assertEquals(Set.of(), NonCrewCarriages.ids(null), "reset drops both hooks");
        assertFalse(NonCrewCarriages.isForceLoadProtected(null, car));
    }
}
