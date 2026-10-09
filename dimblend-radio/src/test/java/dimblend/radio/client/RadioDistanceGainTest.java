package dimblend.radio.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioDistanceGainTest {
    @Test
    void linearCurveReachesZeroAtTheAudibleEdge() {
        assertEquals(1f, RadioSimulationSession.distanceGain(0), 1e-6);
        assertEquals(0.5f, RadioSimulationSession.distanceGain(RadioAcousticController.AUDIBLE_RANGE / 2), 1e-6);
        assertEquals(0f, RadioSimulationSession.distanceGain(RadioAcousticController.AUDIBLE_RANGE), 1e-6);
        assertEquals(0f, RadioSimulationSession.distanceGain(RadioAcousticController.AUDIBLE_RANGE * 2), 1e-6);
    }

    @Test
    void wetFieldKeepsBaseGainInRoomsAndTapersOutAtTheEdge() {
        assertEquals(3f, RadioSimulationSession.wetScale(4, 1), 1e-6);
        assertEquals(3f, RadioSimulationSession.wetScale(64, 1), 1e-6);
        assertEquals(1.5f, RadioSimulationSession.wetScale(RadioAcousticController.AUDIBLE_RANGE - 16, 1), 1e-6);
        assertEquals(0f, RadioSimulationSession.wetScale(RadioAcousticController.AUDIBLE_RANGE, 1), 1e-6);
    }

    @Test
    void acousticIntensityScalesTheEcho() {
        try {
            dimblend.radio.RadioServerConfig.apply(0.5, 0);
            assertEquals(0f, RadioSimulationSession.wetScale(64, 1), 1e-6);
            dimblend.radio.RadioServerConfig.apply(0.5, 2);
            assertEquals(6f, RadioSimulationSession.wetScale(64, 1), 1e-6);
            dimblend.radio.RadioServerConfig.apply(0.5, 0.5);
            assertEquals(0.75f, RadioSimulationSession.wetScale(1.5, 1), 1e-6);
        } finally {
            dimblend.radio.RadioServerConfig.apply(0.5, 1);
        }
    }

    /** The direct sound stays near full level close in, so the echo follows the distance there as a real room's share does. */
    @Test
    void closeToTheRadioTheEchoKeepsARealRoomsShare() {
        assertEquals(1f, RadioSimulationSession.wetScale(0, 1), 1e-6);
        assertEquals(1f, RadioSimulationSession.wetScale(1, 1), 1e-6);
        assertEquals(1.5f, RadioSimulationSession.wetScale(1.5, 1), 1e-6);
        assertEquals(3f, RadioSimulationSession.wetScale(3, 1), 1e-6);
        // Halving the distance takes 6 dB off the echo against the (nearly unchanged) direct sound.
        double halved = 20 * Math.log10(RadioSimulationSession.wetScale(1.25, 1) / RadioSimulationSession.wetScale(2.5, 1));
        assertEquals(-6.02, halved, 0.01);
        float previous = 0;
        for (double distance = 0; distance <= 64; distance += 0.25) {
            float wet = RadioSimulationSession.wetScale(distance, 1);
            assertTrue(wet >= previous, "the echo does not shrink moving away inside the range: " + distance);
            previous = wet;
        }
    }

    @Test
    void aTurnedDownRadioLosesItsEchoFasterThanItsDirectSound() {
        assertEquals(1.5f, RadioSimulationSession.wetScale(4, 0.5f), 1e-6);
        assertEquals(0.3f, RadioSimulationSession.wetScale(4, 0.1f), 1e-6);
        assertEquals(0.75f, RadioSimulationSession.wetScale(1.5, 0.5f), 1e-6);
        assertEquals(3f, RadioSimulationSession.wetScale(4, 1.5f), 1e-6, "turned up, the echo keeps its share");
        String property = "dimblend.radio.acoustic.quietecho";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "0");
            assertEquals(3f, RadioSimulationSession.wetScale(4, 0.2f), 1e-6, "0 keeps the share at any volume");
            System.setProperty(property, "2");
            assertEquals(0.75f, RadioSimulationSession.wetScale(4, 0.5f), 1e-6);
            for (String invalid : new String[] {"quiet", "NaN", "-1", "5", ""}) {
                System.setProperty(property, invalid);
                assertEquals(1.5f, RadioSimulationSession.wetScale(4, 0.5f), 1e-6, invalid);
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void wetGainPropertyIsTunableAndInvalidValuesFallBackInsteadOfThrowingOnTheAudioThread() {
        String property = "dimblend.radio.acoustic.wetgain";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "1.5");
            assertEquals(1.5f, RadioSimulationSession.wetScale(4, 1), 1e-6);
            assertEquals(1.2f, RadioSimulationSession.wetScale(1.2, 1), 1e-6, "below the base gain the distance rules");
            for (String invalid : new String[] {"loud", "NaN", "-1", "Infinity", ""}) {
                System.setProperty(property, invalid);
                assertEquals(3f, RadioSimulationSession.wetScale(4, 1), 1e-6, invalid);
            }
            System.clearProperty(property);
            assertEquals(3f, RadioSimulationSession.wetScale(4, 1), 1e-6);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
