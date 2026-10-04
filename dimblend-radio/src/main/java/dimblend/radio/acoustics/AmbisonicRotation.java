package dimblend.radio.acoustics;

import org.joml.Matrix3d;
import org.joml.Quaterniondc;

/**
 * Turns first-order Ambisonics (Steam Audio's W, Y, Z, X) from one frame's axes into another's:
 * a response simulated in a structure's frame ({@link AcousticFrame}) is turned into world axes
 * before the listener's orientation decodes it. W is unchanged; Y, Z and X turn like the direction
 * they encode, which in Steam Audio's frame is (-Y, Z, -X) (see {@link PathingField#arrival()}).
 */
public final class AmbisonicRotation {
    /** Row-major, acting on (Y, Z, X). */
    private final float[] matrix = new float[9];

    private AmbisonicRotation(Matrix3d rotation) {
        double[] sign = {-1, 1, -1};
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                matrix[row * 3 + column] = (float) (sign[row] * sign[column] * rotation.get(column, row));
            }
        }
    }

    /** @param rotation from the frame the Ambisonics were made in to the frame they are wanted in */
    public static AmbisonicRotation of(Quaterniondc rotation) {
        return new AmbisonicRotation(new Matrix3d().rotation(rotation));
    }

    /** Four coefficients (W, Y, Z, X), turned; {@code sh} is left as it is. */
    public float[] apply(float[] sh) {
        float[] turned = sh.clone();
        for (int row = 0; row < 3; row++) {
            turned[row + 1] = matrix[row * 3] * sh[1] + matrix[row * 3 + 1] * sh[2] + matrix[row * 3 + 2] * sh[3];
        }
        return turned;
    }

    /** Four channels (W, Y, Z, X) of {@code frames} samples, turned in place. */
    public void apply(float[][] channels, int frames) {
        float[] y = channels[1], z = channels[2], x = channels[3];
        for (int i = 0; i < frames; i++) {
            float a = y[i], b = z[i], c = x[i];
            y[i] = matrix[0] * a + matrix[1] * b + matrix[2] * c;
            z[i] = matrix[3] * a + matrix[4] * b + matrix[5] * c;
            x[i] = matrix[6] * a + matrix[7] * b + matrix[8] * c;
        }
    }
}
