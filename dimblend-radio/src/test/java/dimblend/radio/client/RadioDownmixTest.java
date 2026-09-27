package dimblend.radio.client;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 下混单声道：立体声能量平均（旧行为逐位锁定），环绕按角色加权且 LFE 不参与。 */
class RadioDownmixTest {
    private static AudioFormat format(int channels) {
        return new AudioFormat(44100, 16, channels, true, false);
    }

    private static byte[] frames(int channels, int[]... samples) {
        byte[] data = new byte[samples.length * channels * 2];
        for (int f = 0; f < samples.length; f++) {
            for (int c = 0; c < channels; c++) {
                int v = samples[f][c];
                data[(f * channels + c) * 2] = (byte) v;
                data[(f * channels + c) * 2 + 1] = (byte) (v >> 8);
            }
        }
        return data;
    }

    private static int monoAt(byte[] mono, int frame) {
        return (short) ((mono[frame * 2] & 0xFF) | (mono[frame * 2 + 1] << 8));
    }

    @Test
    void stereoIsEnergyAverage() {
        byte[] mono = RadioLibrary.toMono(format(2),
                frames(2, new int[]{1000, 2000}, new int[]{-1, 2})).data();
        assertEquals(1500, monoAt(mono, 0));
        assertEquals(0, monoAt(mono, 1)); // 旧整数除法向零截断：1/2=0
    }

    @Test
    void surround51FrontOnlyKeepsLevel() {
        // 5.1 容器装立体声内容（其余静音）：旧 /6 给 3333，加权后 ≈5395
        byte[] mono = RadioLibrary.toMono(format(6),
                frames(6, new int[]{10000, 10000, 0, 0, 0, 0})).data();
        assertEquals(5395, monoAt(mono, 0), 1);
    }

    @Test
    void surround51CorrelatedKeepsPeak() {
        byte[] mono = RadioLibrary.toMono(format(6),
                frames(6, new int[]{10000, 10000, 10000, 10000, 10000, 10000})).data();
        assertEquals(10000, monoAt(mono, 0), 1);
    }

    @Test
    void lfeOnlyMixesToSilence() {
        byte[] mono = RadioLibrary.toMono(format(6),
                frames(6, new int[]{0, 0, 0, 20000, 0, 0})).data();
        assertEquals(0, monoAt(mono, 0));
    }
}
