package dimblend.radio.client;

import dimblend.radio.acoustics.PathingField;
import dimblend.radio.acoustics.bake.PathingBake;
import dimblend.radio.client.AcousticBakeScheduler.BakeState;
import dimblend.radio.client.AcousticBakeScheduler.Inspection;
import dimblend.radio.client.AcousticIdleGate.Activity;
import dimblend.radio.client.AcousticIdleGate.Allowance;
import dimblend.radio.client.AcousticIdleGate.Status;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticBakeReadoutTest {
    private static final BlockPos RADIO = new BlockPos(5, 64, -7);
    private static final PathingBake BAKE = new PathingBake(RADIO, new long[0], new long[0], new float[812 * 3], 4,
            new byte[3 << 20]);
    private static final double[] QUIET = {0.1, 0.2, 0.1};
    private static final Status CLOSED = new Status(new Allowance(0, 0), false, Activity.PLAYING, new double[] {0.7, 0.2, 0.1}, 60, 60);
    private static final Status IDLE = new Status(new Allowance(1, 3), false, Activity.PLAYING, QUIET, 60, 60);

    private static Inspection radio(BakeState state, PathingBake bake, long[] changed, boolean loaded, boolean needsBake,
            double stableIn, double retryIn, int deferred) {
        return new Inspection(RADIO, new AABB(RADIO), state, bake, changed, loaded, true, needsBake, stableIn, retryIn, 0, 1, 0,
                deferred, 0);
    }

    private static Inspection waiting(boolean loaded, double stableIn, double retryIn, int deferred) {
        return radio(BakeState.WAITING, null, new long[0], loaded, true, stableIn, retryIn, deferred);
    }

    @Test
    void theCompassUsesMinecraftsAxes() {
        assertEquals("N", AcousticBakeReadout.compass(new Vec3(0, 0, -1)));
        assertEquals("E", AcousticBakeReadout.compass(new Vec3(1, 0, 0)));
        assertEquals("S", AcousticBakeReadout.compass(new Vec3(0, 0, 1)));
        assertEquals("NW", AcousticBakeReadout.compass(new Vec3(-1, 0, -1).normalize()));
        assertEquals("N, 30° up", AcousticBakeReadout.compass(new Vec3(0, 0.5, -Math.sqrt(0.75))));
        assertEquals("W, 45° down", AcousticBakeReadout.compass(new Vec3(-1, -1, 0).normalize()));
    }

    @Test
    void theGateSaysWhyItIsOpenOrClosed() {
        assertTrue(AcousticBakeReadout.gate(new Status(new Allowance(8, 10), false, Activity.PAUSED, new double[0], 60, 60), false)
                .contains("away (paused): 8 threads, bakes up to 10 s"));
        assertTrue(AcousticBakeReadout.gate(IDLE, false).contains("CPU idle (load 10% 20% 10%): 1 thread, bakes up to 3 s"));
        assertTrue(AcousticBakeReadout.gate(CLOSED, false).contains("CPU busy (load 70% 20% 10%)"));
        assertTrue(AcousticBakeReadout.gate(new Status(new Allowance(0, 0), false, Activity.PLAYING, new double[] {0.1}, 60, 60), false)
                .contains("measuring CPU load (1 of 3 s)"));
        assertTrue(AcousticBakeReadout.gate(new Status(new Allowance(0, 0), false, Activity.PLAYING, QUIET, 40, 60), false)
                .contains("frames behind (40 of 60 fps)"));
        assertTrue(AcousticBakeReadout.gate(new Status(new Allowance(0, 0), false, Activity.PLAYING,
                new double[] {0.1, Double.NaN, 0.1}, 60, 60), false).contains("CPU load unknown"));
        assertTrue(AcousticBakeReadout.gate(new Status(new Allowance(0, 0), true, Activity.PAUSED, QUIET, 60, 60), false)
                .contains("loading"));
        assertTrue(AcousticBakeReadout.gate(CLOSED, true).contains("baking"));
    }

    @Test
    void aDueBakeSaysWhatItWaitsFor() {
        assertEquals("waits for chunks to load", AcousticBakeReadout.waiting(waiting(false, 0, 0, 0), IDLE, false));
        assertEquals("retries in 9 s (0 failed)", AcousticBakeReadout.waiting(waiting(true, 0, 8.2, 0), IDLE, false));
        assertEquals("once unchanged for 3 more s", AcousticBakeReadout.waiting(waiting(true, 2.4, 0, 0), IDLE, false));
        assertEquals("after the running bake", AcousticBakeReadout.waiting(waiting(true, 0, 0, 0), IDLE, true));
        assertEquals("waits for an idle client", AcousticBakeReadout.waiting(waiting(true, 0, 0, 0), CLOSED, false));
        assertEquals("2400 probes: waits until you are away", AcousticBakeReadout.waiting(waiting(true, 0, 0, 2400), IDLE, false));
        assertEquals("starting", AcousticBakeReadout.waiting(waiting(true, 0, 0, 0), IDLE, false));
    }

    @Test
    void aBakeShowsItsSizeAndWhatChanged() {
        var valid = radio(BakeState.VALID, BAKE, new long[0], true, false, 0, 0, 0);
        assertEquals("§aVALID · 812 probes · 4-block cells · 3.0 MiB", AcousticBakeReadout.headline(valid));
        assertEquals("from disk, region unchanged", AcousticBakeReadout.detail(valid, IDLE, false));
        var stale = radio(BakeState.STALE, BAKE, new long[] {1, 2}, true, true, 3, 0, 0);
        assertEquals("2 sections changed; routes validated; rebake once unchanged for 3 more s",
                AcousticBakeReadout.detail(stale, IDLE, false));
        var lines = AcousticBakeReadout.lines(IDLE, false, List.of(stale), Vec3.atCenterOf(RADIO).add(0, 0, 10), pos -> null);
        assertTrue(lines.stream().anyMatch(line -> line.contains("5 64 -7, 10 m")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(line -> line.contains("path: none")));
    }

    @Test
    void thePathShowsItsLengthDirectionAndBands() {
        // Arrives from +z (south): Steam Audio's X term is negative.
        var field = new PathingField(new float[] {1, 0.5f, 0.25f}, new float[] {0.2f, 0, 0, -0.1f}, 42.2f);
        assertEquals("path: 42 m, from S; low/mid/high 0/-6/-12 dB", AcousticBakeReadout.path(field));
        assertTrue(AcousticBakeReadout.path(new PathingField(new float[] {0, 0.5f, 0.25f}, new float[] {0.2f, 0, 0, 0}, 5))
                .contains("no direction; low/mid/high -inf/"));
    }
}
