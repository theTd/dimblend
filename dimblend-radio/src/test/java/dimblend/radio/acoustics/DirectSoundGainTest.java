package dimblend.radio.acoustics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectSoundGainTest {
    @Test void preservesDistanceAirAndFrequencyDependentTransmission() {
        var gain = new DirectSoundGain();
        var params = new SteamAudio.DirectParams();
        params.distance = 0.5f;
        params.occlusion = 0;
        params.air = new float[]{1,0.8f,0.6f};
        params.transmission = new float[]{0.35f,0.2f,0.08f};
        float level = gain.prepare(params);
        assertEquals(0.175f,level,1e-6);
        assertEquals(1,gain.equalization().air[0],1e-6);
        assertEquals(0.08,level*gain.equalization().air[1],1e-6);
        assertEquals(0.024,level*gain.equalization().air[2],1e-6);
    }

    @Test void silenceAndUnblockingSettleWithinOneBlockWithoutSdkExponentialLag() {
        var gain = new DirectSoundGain();
        float[] samples = new float[512];
        java.util.Arrays.fill(samples,1);
        gain.apply(samples,1,48000);
        java.util.Arrays.fill(samples,1);
        gain.apply(samples,0,48000);
        assertTrue(samples[0]>0&&samples[0]<1);
        assertEquals(0,samples[239],1e-6);
        assertEquals(0,samples[511],1e-6);
        java.util.Arrays.fill(samples,1);
        gain.apply(samples,1,48000);
        assertEquals(1,samples[239],1e-6);
    }

    @Test void completeOcclusionDoesNotCreateInvalidEqualizerCoefficients() {
        var gain=new DirectSoundGain();
        var params=new SteamAudio.DirectParams();
        params.occlusion=0;
        params.transmission=new float[3];
        assertEquals(0,gain.prepare(params));
        assertArrayEquals(new float[]{1,1,1},gain.equalization().air);
    }
}
