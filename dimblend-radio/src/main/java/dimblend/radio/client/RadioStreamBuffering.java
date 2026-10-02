package dimblend.radio.client;

import dimblend.radio.acoustics.SteamRenderer;

/** Start short, then add headroom only when the device actually runs dry. */
public final class RadioStreamBuffering {
    public static int bufferCount(float sampleRate) {
        return Math.max(2, (int) Math.ceil(sampleRate * 0.025 / SteamRenderer.FRAME));
    }

    public static final class State {
        private final int maximum;
        private int target;

        public State(float rate) {
            target = bufferCount(rate);
            maximum = Math.max(target, (int) Math.ceil(rate * 0.090 / SteamRenderer.FRAME));
        }

        public int target() { return target; }

        /** Keep learned headroom for this playback; shrinking it repeatedly would cause oscillation. */
        public int underrun() {
            target = Math.min(maximum, target + 2);
            return target;
        }
    }

    private RadioStreamBuffering() { }
}
