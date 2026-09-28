package dimblend.radio.client;

/** One-second fade at 20 client ticks/s, reversible if the radio stops during the fade. */
public final class RadioMusicFade {
    private int remaining = 20;

    public float tick(boolean suppress) {
        this.remaining = Math.clamp(this.remaining + (suppress ? -1 : 1), 0, 20);
        return gain();
    }

    public float gain() {
        return this.remaining / 20.0f;
    }

    public void reset() {
        this.remaining = 20;
    }
}
