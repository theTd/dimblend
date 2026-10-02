package dimblend.radio.client;

import net.minecraft.world.phys.Vec3;

/**
 * Equal-power stereo panning for a radio outside the Steam Audio path (still initializing,
 * failed, or not among the simulated radios). It keeps the direction and the acoustic loudness
 * curve, so leaving the simulated set narrows the image instead of collapsing it to the centre.
 */
final class RadioStereoPanner {
    private float left = Float.NaN, right = Float.NaN;

    /** One mono block to stereo; gains ramp from the previous block's to avoid zipper noise. */
    float[][] process(float[] mono, Vec3 relativeSource, Vec3 ahead, Vec3 up) {
        float[] target = gains(relativeSource, ahead, up);
        if (Float.isNaN(left)) {
            left = target[0];
            right = target[1];
        }
        float[][] output = new float[2][mono.length];
        for (int i = 0; i < mono.length; i++) {
            float weight = (i + 1f) / mono.length;
            output[0][i] = mono[i] * (left + (target[0] - left) * weight);
            output[1][i] = mono[i] * (right + (target[1] - right) * weight);
        }
        left = target[0];
        right = target[1];
        return output;
    }

    /** The next block starts at its target gains (after silence there is nothing to ramp from). */
    void reset() {
        left = right = Float.NaN;
    }

    /** {left, right}: sin/cos law over the source's lateral position, scaled by the distance curve. */
    static float[] gains(Vec3 relativeSource, Vec3 ahead, Vec3 up) {
        Vec3 rightAxis = ahead.cross(up);
        double distance = relativeSource.length(), axisLength = rightAxis.length();
        double pan = distance < 1e-6 || axisLength < 1e-6 ? 0 : relativeSource.dot(rightAxis) / (distance * axisLength);
        double angle = (Math.max(-1, Math.min(1, pan)) + 1) * Math.PI / 4;
        float gain = RadioSimulationSession.distanceGain(distance);
        return new float[] {(float) Math.cos(angle) * gain, (float) Math.sin(angle) * gain};
    }
}
