package dimblend.worldgen;

/** Admission is closed until every gameplay signal has stayed healthy for the recovery period. */
public final class PregenAdmission {
    public enum Reason { RECOVERING, CPU, CLIENT, POOL, TICKS, FOREIGN, PLAYER_NEAR, DISABLED, READY }

    private long healthySince = -1;
    private Reason reason = Reason.RECOVERING;

    public Reason update(long nowMs, double cpuLoad, double cpuLimit, boolean clientReady,
            boolean poolBusy, double averageTickMs, double lastTickMs, double tickLimit, long recoveryMs) {
        if (!Double.isFinite(cpuLoad) || cpuLoad < 0 || cpuLoad >= cpuLimit) return block(Reason.CPU);
        if (!clientReady) return block(Reason.CLIENT);
        if (poolBusy) return block(Reason.POOL);
        if (!Double.isFinite(averageTickMs) || !Double.isFinite(lastTickMs)
                || averageTickMs >= tickLimit || lastTickMs >= tickLimit) return block(Reason.TICKS);
        if (healthySince < 0 || nowMs < healthySince) healthySince = nowMs;
        reason = nowMs - healthySince >= recoveryMs ? Reason.READY : Reason.RECOVERING;
        return reason;
    }

    public Reason block(Reason why) {
        healthySince = -1;
        reason = why;
        return reason;
    }

    public Reason reason() { return reason; }

    public static boolean poolBusy(int parallelism, int active, long submissions, long workerTasks) {
        int reserved = Math.max(1, parallelism / 2);
        return active >= Math.max(1, parallelism - reserved)
                || submissions + workerTasks >= Math.max(1, parallelism);
    }

    public static boolean canIssue(int inFlight, int cancelling, int cap) {
        return cap > 0 && cancelling == 0 && inFlight < cap;
    }

    public static boolean framesHealthy(int fps, int targetFps, long frameNanos) {
        return targetFps > 0 && fps >= targetFps * 0.95
                && frameNanos > 0 && frameNanos <= 1_000_000_000.0 / targetFps * 1.2;
    }

    public static boolean outsidePlayerView(int x, int z, int playerX, int playerZ, int viewDistance,
            int dependencyRadius, int proximityRadius) {
        int radius = Math.max(proximityRadius, viewDistance + dependencyRadius);
        return Math.max(Math.abs((long) x - playerX), Math.abs((long) z - playerZ)) > radius;
    }
}
