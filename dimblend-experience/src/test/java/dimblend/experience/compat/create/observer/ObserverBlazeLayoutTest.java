package dimblend.experience.compat.create.observer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Pins the observer blaze's raised layout and the cushion model that goes with it. The renderer, the flame particles
 * and the resource model are three separate places; these assertions are what keeps them from drifting apart.
 */
class ObserverBlazeLayoutTest {
    /** The block's own bottom face is the datum: the blaze stands half a block up, the spyglass one block higher. */
    private static final float EPSILON = 1.0E-4F;

    @Test
    void blazeStandsOnAHalfBlockCushion() {
        assertEquals(0.5F, ObserverBlazeLayout.CUSHION_HEIGHT, EPSILON);
        assertEquals(ObserverBlazeLayout.CUSHION_HEIGHT, ObserverBlazeLayout.BLAZE_RAISE, EPSILON);
    }

    @Test
    void spyglassFloatsHalfABlockAboveTheRaisedHead() {
        // Create raises the blaze's head by one block from the block's top face; half a block above that is 2.0.
        assertEquals(2.0F, ObserverBlazeLayout.SPYGLASS_Y, EPSILON);
        assertEquals(ObserverBlazeLayout.BLAZE_RAISE + 1.5F, ObserverBlazeLayout.SPYGLASS_Y, EPSILON);
    }

    @Test
    void flameParticlesRiseWithTheBlaze() {
        // Create's own puff sat at 0.95 above the block; the raised blaze moves it up by exactly the cushion.
        assertEquals(1.45F, ObserverBlazeLayout.FLAME_BASE_Y, EPSILON);
        assertEquals(0.3F, ObserverBlazeLayout.FLAME_SPREAD_Y, EPSILON);
        assertTrue(ObserverBlazeLayout.FLAME_BASE_Y > ObserverBlazeLayout.CUSHION_HEIGHT,
                "flames must start above the cushion, got " + ObserverBlazeLayout.FLAME_BASE_Y);
        assertTrue(ObserverBlazeLayout.FLAME_BASE_Y + ObserverBlazeLayout.FLAME_SPREAD_Y < ObserverBlazeLayout.SPYGLASS_Y,
                "flames must stay below the spyglass");
    }

    /**
     * The model file is the cushion's only definition, so a wrong element here is a block that renders nothing (the
     * pre-cushion model was empty) -- this test reads the real resource, not a copy.
     */
    @Test
    void cushionModelIsAHalfBlockTallCubeWearingTheRedSeatTextures() throws IOException {
        String json = readResource("/assets/dimblend_experience/models/block/observer_blaze.json");
        assertTrue(json.contains("\"from\": [0, 0, 0]"), "the cushion must start at the block's bottom face: " + json);
        assertTrue(json.contains("\"to\": [16, 8, 16]"), "the cushion must be a half block tall: " + json);
        assertTrue(json.contains("create:block/seat/top_red"), "the cushion top must be the red seat texture: " + json);
        assertTrue(json.contains("create:block/seat/side_red"), "the cushion sides must be the red seat texture: " + json);
        assertTrue(json.contains("create:block/seat/bottom"), "the cushion underside must use Create's seat bottom: " + json);
    }

    /** The blockstate must keep pointing at that model; a blockstate with no model is an invisible cushion. */
    @Test
    void blockstatePointsAtTheCushionModel() throws IOException {
        String json = readResource("/assets/dimblend_experience/blockstates/observer_blaze.json");
        assertTrue(json.contains("dimblend_experience:block/observer_blaze"), "unexpected blockstate: " + json);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = ObserverBlazeLayoutTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "missing resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}