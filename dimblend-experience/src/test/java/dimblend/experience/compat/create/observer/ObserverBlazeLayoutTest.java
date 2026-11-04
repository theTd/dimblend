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
    /** The block's own bottom face is the datum: the blaze stands on the cushion, the spyglass above its head. */
    private static final float EPSILON = 1.0E-4F;

    @Test
    void blazeStandsOnAHalfBlockCushion() {
        assertEquals(0.5F, ObserverBlazeLayout.CUSHION_HEIGHT, EPSILON);
        assertEquals(6.0F / 16.0F, ObserverBlazeLayout.BLAZE_MODEL_BOTTOM, EPSILON);
    }

    /**
     * The cushion-alignment value is still the base the layout is built from; the user's lift is
     * added on top of it, so the blaze deliberately no longer meets the cushion.
     *
     * <p>History: the original regression was "lifting by the cushion height alone left 6/16 of air
     * between flames and cushion". That alignment is preserved here as
     * {@code CUSHION_HEIGHT - BLAZE_MODEL_BOTTOM}; what changed is that {@link
     * ObserverBlazeLayout#RAISE_ABOVE_CUSHION} is added deliberately, by the user's request. It was
     * 1.0 for a day, then 0.5, and is a quarter block as of 2026-11-04.
     */
    @Test
    void theBlazesUndersideClearsTheCushionByTheRequestedLift() {
        float aligned = ObserverBlazeLayout.CUSHION_HEIGHT - ObserverBlazeLayout.BLAZE_MODEL_BOTTOM;
        assertEquals(aligned + ObserverBlazeLayout.RAISE_ABOVE_CUSHION,
                ObserverBlazeLayout.BLAZE_RAISE, EPSILON,
                "the raise is the cushion alignment plus the user's requested lift");
        assertEquals(0.25F, ObserverBlazeLayout.RAISE_ABOVE_CUSHION, EPSILON,
                "the user walked the lift down to a quarter block on 2026-11-04");
        assertEquals(0.375F, ObserverBlazeLayout.BLAZE_RAISE, EPSILON);
    }

    @Test
    void spyglassFloatsHalfABlockAboveTheRaisedHead() {
        // Create's blaze model tops out at 14/16; half a block above that, plus the raise.
        assertEquals(ObserverBlazeLayout.BLAZE_RAISE + 0.875F + 0.5F, ObserverBlazeLayout.SPYGLASS_Y, EPSILON);
        assertEquals(1.75F, ObserverBlazeLayout.SPYGLASS_Y, EPSILON,
                "with the quarter-block lift the spyglass centre sits at 1.75");
    }

    @Test
    void flameParticlesRiseWithTheBlaze() {
        // Create's own puff sat at 0.95 above the block; the raised blaze moves it up by exactly the raise.
        assertEquals(ObserverBlazeLayout.BLAZE_RAISE + 0.95F, ObserverBlazeLayout.FLAME_BASE_Y, EPSILON);
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
    void cushionModelIsAHalfBlockTallCubeWearingTheOrangeSeatTextures() throws IOException {
        String json = readResource("/assets/dimblend_experience/models/block/observer_blaze.json");
        assertTrue(json.contains("\"from\": [0, 0, 0]"), "the cushion must start at the block's bottom face: " + json);
        assertTrue(json.contains("\"to\": [16, 8, 16]"), "the cushion must be a half block tall: " + json);
        assertTrue(json.contains("create:block/seat/top_orange"),
                "the cushion top must be the orange seat texture: " + json);
        assertTrue(json.contains("create:block/seat/side_orange"),
                "the cushion sides must be the orange seat texture: " + json);
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