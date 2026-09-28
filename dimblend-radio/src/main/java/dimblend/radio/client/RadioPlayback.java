package dimblend.radio.client;

/** One local play-through. Kept across channel restarts; server clock corrections never rewind it. */
public final class RadioPlayback {
    private final int nonce;
    private final String hash;
    private final long startTick;
    private final double duration;
    private double position;
    private long sampledAt;
    private boolean paused;
    private boolean finished;

    public RadioPlayback(int nonce, String hash, long startTick, double duration, double offset, long now) {
        this.nonce = nonce;
        this.hash = hash;
        this.startTick = startTick;
        this.duration = duration;
        this.position = Math.clamp(offset, 0.0, duration);
        this.sampledAt = now;
    }

    public boolean matches(int nonce, String hash, long startTick) {
        return this.nonce == nonce && this.hash.equals(hash) && this.startTick == startTick;
    }

    public double duration() {
        return this.duration;
    }

    public double position(long now) {
        if (this.finished) {
            return this.duration;
        }
        double elapsed = this.paused ? 0.0 : Math.max(0.0, (now - this.sampledAt) / 1e9);
        return Math.min(this.duration, this.position + elapsed);
    }

    /** Call on pause transitions, as well as ticks: a paused single-player game pauses its audio. */
    public void setPaused(boolean paused, long now) {
        this.position = position(now);
        this.sampledAt = now;
        this.paused = paused;
    }

    public boolean finished(long now) {
        return this.finished || position(now) >= this.duration;
    }

    /** Only after the channel stops, never just because OpenAL has read ahead to EOF. */
    public void channelEnded(boolean pcmExhausted) {
        if (pcmExhausted) {
            this.finished = true;
        }
    }
}
