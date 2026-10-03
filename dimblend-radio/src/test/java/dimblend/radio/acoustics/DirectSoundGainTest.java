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

    @Test void partialOcclusionMufflesHighsMoreThanLows() {
        var gain = new DirectSoundGain();
        var params = new SteamAudio.DirectParams();
        params.occlusion = 0.25f;
        params.transmission = new float[3];
        float level = gain.prepare(params);
        assertEquals(0.25, level * gain.equalization().air[0], 1e-6);
        assertEquals(0.125, level * gain.equalization().air[1], 1e-6);
        assertEquals(0.0625, level * gain.equalization().air[2], 1e-6);
    }

    @Test void transmissionFillsInWhatPartialOcclusionRemoves() {
        var gain = new DirectSoundGain();
        var params = new SteamAudio.DirectParams();
        params.occlusion = 0.25f;
        params.transmission = new float[]{0.35f, 0.2f, 0.08f};
        float level = gain.prepare(params);
        float[] expected = {0.25f + 0.75f * 0.35f, 0.125f + 0.875f * 0.2f, 0.0625f + 0.9375f * 0.08f};
        for (int band = 0; band < 3; band++) {
            assertEquals(expected[band], level * gain.equalization().air[band], 1e-6, "band " + band);
        }
    }

    @Test void blockedPathsFadeInsteadOfJumping() {
        double block = 512 / 44100.0;
        var gain = new DirectSoundGain(block);
        var params = new SteamAudio.DirectParams();
        params.occlusion = 1;
        params.transmission = new float[] {0.01f, 0.01f, 0.01f};
        assertEquals(1, gain.prepare(params), 1e-6, "the first block takes its target");
        params.occlusion = 0;
        double weight = 1 - Math.exp(-block / DirectSoundGain.SHADING_SECONDS);
        assertEquals(-40 * weight, 20 * Math.log10(gain.prepare(params)), 1e-3, "one block moves part of the way, in dB");
        for (int i = 0; i < 100; i++) gain.prepare(params);
        assertEquals(0.01, gain.prepare(params), 1e-4, "settles after a second");
        gain.reset();
        params.occlusion = 1;
        assertEquals(1, gain.prepare(params), 1e-6, "a reset starts at the new target");
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
