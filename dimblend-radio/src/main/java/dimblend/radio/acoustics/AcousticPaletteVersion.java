package dimblend.radio.acoustics;

/** Client palette mutations invalidate immutable acoustic copies, including chunk packet reads. */
public interface AcousticPaletteVersion {
    long dimblend$acousticVersion();
    void dimblend$observeAcoustics(long generation);
}
