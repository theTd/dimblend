package dimblend.radio;

/**
 * The per-jukebox volume rule the user asked for on 2026-11-03.
 *
 * <p><b>The old rule.</b> Volume came only from the side redstone signal: {@code side * 10} for
 * 1..15, so 10%..150%, and a side signal of 0 meant "stop playing". Every jukebox on the map obeyed
 * the same mapping.
 *
 * <p><b>The new rule.</b> Each jukebox may carry a fixed volume in whole steps of 10 from 0% to
 * 150%:
 * <ul>
 *   <li><b>0% is "dynamic"</b> — the old behaviour: the side signal sets the volume, and no side
 *       signal means no playback.</li>
 *   <li><b>anything else is "fixed"</b> — <i>while</i> a side signal is present (which is what tells
 *       the radio to play at all, since the top signal selects the station and the side signal
 *       gates playback), the volume is that fixed value and no longer follows the signal's
 *       strength.</li>
 * </ul>
 *
 * <p>"Follow the external input" therefore still decides <i>whether</i> it plays; the new setting
 * only decides <i>how loud</i>, once it is playing. That is the reading in the requirement: "若为
 * 0% 则跟随外部输入红石信号调整音量 若不为 0% 则在外部有信号输入的时候 播送固定音量".
 *
 * <p>Pure, so the boundary cases (no signal, 0%, 150%, 100%) are pinned by a test instead of being
 * discovered in game.
 */
public final class RadioVolumeRule {

    /** The "dynamic" value: follow the side signal. */
    public static final int DYNAMIC = 0;
    /** Step the user asked for: 10 percentage points. */
    public static final int STEP = 10;
    /** Upper end of the user's 0%..150% range. */
    public static final int MAX = 150;

    /** What one jukebox should do this tick. */
    public enum Verdict {
        /** No side signal and dynamic volume: the radio does not play. */
        SILENT,
        /** Play at {@link #volumePercent}. */
        PLAY
    }

    /** The result of the rule: whether to play, and at what percentage. */
    public record Setting(Verdict verdict, int volumePercent) {
        public boolean playing() {
            return verdict == Verdict.PLAY;
        }
    }

    /**
     * Decides one jukebox's volume.
     *
     * @param sideSignal   the side redstone signal, 0..15; 0 means "no external input"
     * @param fixedPercent the per-block setting: {@link #DYNAMIC} (0) or 10..{@link #MAX}
     */
    public static Setting decide(int sideSignal, int fixedPercent) {
        int fixed = clampToStep(fixedPercent);
        boolean hasInput = sideSignal > 0;
        if (fixed == DYNAMIC) {
            // Old behaviour: no signal, no playback; otherwise the signal sets the loudness.
            return hasInput
                    ? new Setting(Verdict.PLAY, RadioSignals.volumePercent(sideSignal))
                    : new Setting(Verdict.SILENT, 0);
        }
        // Fixed volume: the signal still gates playback, but its strength no longer sets loudness.
        return hasInput
                ? new Setting(Verdict.PLAY, fixed)
                : new Setting(Verdict.SILENT, 0);
    }

    /**
     * Clamps a requested percentage to 0..150 and to a whole step of 10.
     *
     * <p>A stored value that somehow misses the grid (an old config, a hand-edited save) snaps to
     * the nearest step rather than being rejected: the radio must keep working.
     */
    public static int clampToStep(int percent) {
        if (percent <= DYNAMIC) {
            return DYNAMIC;
        }
        int clamped = Math.min(MAX, percent);
        int snapped = Math.round(clamped / (float) STEP) * STEP;
        return Math.max(STEP, Math.min(MAX, snapped));
    }

    /** True when a stored value would be changed by {@link #clampToStep}. */
    public static boolean isOnGrid(int percent) {
        return percent >= 0 && percent <= MAX && percent % STEP == 0;
    }

    private RadioVolumeRule() {
    }
}