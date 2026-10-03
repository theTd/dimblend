package dimblend.radio.acoustics;

/**
 * Thrown while {@code phonon.dll} is not on disk yet (background download running) or its last
 * download failed. Callers play stereo-panned sound and retry later; this must never poison
 * {@link AcousticAvailability} the way a broken GPU does.
 */
public final class PhononNotReadyException extends IllegalStateException {
    public PhononNotReadyException(String message) {
        super(message);
    }
}
