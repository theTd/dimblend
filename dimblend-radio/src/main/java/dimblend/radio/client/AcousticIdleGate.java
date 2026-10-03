package dimblend.radio.client;

import java.lang.management.ManagementFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.world.phys.Vec3;

/**
 * Whether the client has capacity to spare for an acoustic bake, and how much. Bakes cannot be
 * stopped once started, so the answer bounds both the bake threads and the estimated bake time.
 * <ul>
 * <li><b>Away</b> (paused, window in the background, or no movement for {@link #AWAY_SECONDS}): up
 * to half the logical processors, at most 8, for bakes of up to {@link #AWAY_BUDGET_SECONDS}.</li>
 * <li><b>CPU idle</b> (system load below {@link #QUIET_LOAD} over the last {@link #LOAD_SAMPLES}
 * seconds without a bake running, and smooth frames): one thread (two on 12 or more logical
 * processors) for bakes of up to {@link #IDLE_BUDGET_SECONDS}. The player may be moving.</li>
 * <li>Never without a level, while loading or connecting, or during a resource reload.</li>
 * </ul>
 */
public final class AcousticIdleGate {
    static final double AWAY_SECONDS = 30;
    static final double QUIET_LOAD = 0.5;
    static final int LOAD_SAMPLES = 3;
    static final double IDLE_BUDGET_SECONDS = 3;
    static final double AWAY_BUDGET_SECONDS = 10;
    private static final long SECOND = 1_000_000_000L;

    /** Bake threads and the longest estimated bake to start; no threads means not now. */
    public record Allowance(int threads, double seconds) {
        static final Allowance NONE = new Allowance(0, 0);

        public boolean open() { return threads > 0; }
    }

    /**
     * @param recentLoads system CPU load (0 to 1) once a second, newest last; NaN where unknown
     */
    static Allowance decide(boolean loading, boolean away, double[] recentLoads, boolean smoothFrames, int processors) {
        if (loading) return Allowance.NONE;
        if (away) return new Allowance(Math.max(1, Math.min(8, processors / 2)), AWAY_BUDGET_SECONDS);
        if (!smoothFrames || recentLoads.length < LOAD_SAMPLES) return Allowance.NONE;
        for (int i = recentLoads.length - LOAD_SAMPLES; i < recentLoads.length; i++) {
            if (!(recentLoads[i] < QUIET_LOAD)) return Allowance.NONE;
        }
        return new Allowance(processors >= 12 ? 2 : 1, IDLE_BUDGET_SECONDS);
    }

    private final double[] loads = new double[LOAD_SAMPLES];
    private int loadCount;
    private long lastLoadSample, lastMovement;
    private Vec3 lastPosition;
    private float lastYaw, lastPitch;
    private boolean loadUnavailable;

    /**
     * Called every client tick.
     *
     * @param baking a bake is running: its threads would count as load, so samples restart after it
     */
    public Allowance sample(Minecraft mc, boolean baking, long now) {
        trackMovement(mc, now);
        if (baking) {
            loadCount = 0;
            lastLoadSample = now;
        } else if (now - lastLoadSample >= SECOND) {
            lastLoadSample = now;
            System.arraycopy(loads, 1, loads, 0, LOAD_SAMPLES - 1);
            loads[LOAD_SAMPLES - 1] = systemLoad();
            loadCount = Math.min(LOAD_SAMPLES, loadCount + 1);
        }
        boolean loading = mc.level == null || mc.player == null || mc.getOverlay() != null
                || mc.screen instanceof ReceivingLevelScreen || mc.screen instanceof LevelLoadingScreen
                || mc.screen instanceof ProgressScreen || mc.screen instanceof GenericMessageScreen;
        boolean away = mc.isPaused() || !mc.isWindowActive() || now - lastMovement >= (long) (AWAY_SECONDS * SECOND);
        double[] recent = java.util.Arrays.copyOfRange(loads, LOAD_SAMPLES - loadCount, LOAD_SAMPLES);
        return decide(loading, away, recent, smoothFrames(mc), Runtime.getRuntime().availableProcessors());
    }

    private void trackMovement(Minecraft mc, long now) {
        if (lastMovement == 0) lastMovement = now;
        var player = mc.player;
        if (player == null) return;
        Vec3 position = player.position();
        float yaw = player.getYRot(), pitch = player.getXRot();
        if (lastPosition == null || position.distanceToSqr(lastPosition) > 0.01 * 0.01
                || Math.abs(yaw - lastYaw) > 0.1f || Math.abs(pitch - lastPitch) > 0.1f) {
            lastMovement = now;
        }
        lastPosition = position;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    /** Frames keep up with the lower of 60 fps and the frame rate limit. */
    private static boolean smoothFrames(Minecraft mc) {
        int limit = mc.options.framerateLimit().get();
        return mc.getFps() >= 0.9 * Math.min(60, limit);
    }

    /** NaN when the platform does not report it: then only being away opens the gate. */
    private double systemLoad() {
        if (loadUnavailable) return Double.NaN;
        try {
            var bean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = bean.getCpuLoad();
            return load >= 0 ? load : Double.NaN;
        } catch (RuntimeException | LinkageError unavailable) {
            loadUnavailable = true;
            return Double.NaN;
        }
    }
}
