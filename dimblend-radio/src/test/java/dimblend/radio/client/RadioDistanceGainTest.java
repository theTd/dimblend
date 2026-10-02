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
        assertEquals(3f, RadioSimulationSession.wetScale(4), 1e-6);
        assertEquals(3f, RadioSimulationSession.wetScale(64), 1e-6);
        assertEquals(1.5f, RadioSimulationSession.wetScale(RadioAcousticController.AUDIBLE_RANGE - 16), 1e-6);
        assertEquals(0f, RadioSimulationSession.wetScale(RadioAcousticController.AUDIBLE_RANGE), 1e-6);
    }

    @Test
    void wetGainPropertyIsTunableAndInvalidValuesFallBackInsteadOfThrowingOnTheAudioThread() {
        String property = "dimblend.radio.acoustic.wetgain";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "1.5");
            assertEquals(1.5f, RadioSimulationSession.wetScale(4), 1e-6);
            for (String invalid : new String[] {"loud", "NaN", "-1", "Infinity", ""}) {
                System.setProperty(property, invalid);
                assertEquals(3f, RadioSimulationSession.wetScale(4), 1e-6, invalid);
            }
            System.clearProperty(property);
            assertEquals(3f, RadioSimulationSession.wetScale(4), 1e-6);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
