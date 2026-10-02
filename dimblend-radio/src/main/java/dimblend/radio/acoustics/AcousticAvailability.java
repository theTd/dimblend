package dimblend.radio.acoustics;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import net.neoforged.fml.ModList;

/**
 * Whether the Steam Audio pipeline can run in this client. Platform and opt-outs are static; the
 * GPU requirement is learned from the first reflection engine construction, so players who never
 * meet a radio never initialize OpenCL.
 */
public final class AcousticAvailability {
    /** {@code null} until the first reflection engine was attempted. */
    private static volatile Boolean gpu;

    /** Platform, configuration and mod-compatibility requirements. */
    public static boolean configured() {
        return !"false".equalsIgnoreCase(System.getProperty("dimblend.radio.reverb"))
                && !"false".equalsIgnoreCase(System.getProperty("dimblend.radio.acoustic.gpu"))
                && System.getProperty("os.name", "").startsWith("Windows")
                && "amd64".equals(System.getProperty("os.arch"))
                && !soundPhysicsLoaded();
    }

    /** Requirements met and no machine-level GPU failure observed. */
    public static boolean possible() {
        return configured() && !Boolean.FALSE.equals(gpu);
    }

    public static void gpuAvailable() {
        gpu = Boolean.TRUE;
    }

    /**
     * Only a failure before any engine ever worked says the machine has no usable GPU; later ones
     * are per-session (device loss, exhaustion) and keep new radios on the acoustic path.
     */
    public static synchronized void gpuUnavailable(Throwable error) {
        if (gpu != null) {
            return;
        }
        gpu = Boolean.FALSE;
        // The Sodium mirror has no consumer any more; release it and stop the tee.
        SectionGeometryCache.clear();
        DimBlendRadio.LOGGER.warn("[radio] Steam Audio GPU acoustics unavailable; new radios use vanilla positional sound", error);
    }

    private static boolean soundPhysicsLoaded() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded("sound_physics_remastered");
    }

    private AcousticAvailability() { }
}
