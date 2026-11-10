package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dimblend.worldgen.CorridorTrackProfile;
import org.junit.jupiter.api.Test;

class ChassisLaneRuleTest {
    @Test
    void knownLanesMatchTheServerProfileSwitch() {
        assertEquals(CorridorTrackProfile.SURFACE.trackId(), ChassisLaneRule.trackId("surface"));
        assertEquals(CorridorTrackProfile.UNDERGROUND.trackId(), ChassisLaneRule.trackId("underground"));
        assertEquals(CorridorTrackProfile.NETHER.trackId(), ChassisLaneRule.trackId("nether"));
        assertEquals(CorridorTrackProfile.END.trackId(), ChassisLaneRule.trackId("end"));
        assertEquals(CorridorTrackProfile.TWILIGHT.trackId(), ChassisLaneRule.trackId("twilight"));
        assertEquals(CorridorTrackProfile.STARLIGHT.trackId(), ChassisLaneRule.trackId("starlight"));
        assertEquals(CorridorTrackProfile.OTHERSIDE.trackId(), ChassisLaneRule.trackId("deeperdarker"));
        assertEquals(CorridorTrackProfile.AETHER.trackId(), ChassisLaneRule.trackId("aether"));
        assertEquals(CorridorTrackProfile.VOIDSCAPE.trackId(), ChassisLaneRule.trackId("voidscape"));
    }

    @Test
    void unknownModdedLaneUsesTheModFallback() {
        assertEquals(CorridorTrackProfile.MOD.trackId(), ChassisLaneRule.trackId("some_modded_lane"));
        assertEquals(CorridorTrackProfile.MOD.trackId(), ChassisLaneRule.trackId("mod"));
    }

    @Test
    void unsyncedLaneNeverGuesses() {
        assertNull(ChassisLaneRule.trackId(null));
        assertNull(ChassisLaneRule.trackId(""));
        assertNull(ChassisLaneRule.trackId("unknown"));
    }
}
