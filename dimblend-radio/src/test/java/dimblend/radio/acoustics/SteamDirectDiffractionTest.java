package dimblend.radio.acoustics;

import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Volumetric occlusion on the CPU direct engine ({@link AcousticDiffraction}): a listener walking
 * behind the edge of a wall hears the direct sound fade instead of cutting off.
 */
class SteamDirectDiffractionTest {
    private static final String RADIUS = "dimblend.radio.acoustic.diffraction";
    private static final String SAMPLES = "dimblend.radio.acoustic.diffraction.samples";
    private static final AcousticVoxelTrace.Cell STONE = new AcousticVoxelTrace.Cell(Shapes.block(), AcousticMaterials.STONE);
    /** One block thick wall in the plane x = 0, ending in a vertical edge at z = 0 (cells z <= -1). */
    private static final AcousticVoxelTrace.Lookup HALF_WALL =
            pos -> pos.getX() == 0 && Math.abs(pos.getY()) <= 8 && pos.getZ() <= -1 && pos.getZ() >= -40 ? STONE : null;
    private static final Vec3 SOURCE = new Vec3(4.5, 0.5, -4.5);

    @Test
    void walkingBehindAnEdgeFadesTheDirectSound() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1)) {
            float previous = Float.POSITIVE_INFINITY, atBoundary = Float.NaN;
            boolean partial = false, wasClear = true;
            // At z >= 24 the whole source sphere is in view; the centre line is blocked below z ~ 7.07.
            for (double z = 30; z >= -10; z -= 0.5) {
                Vec3 listener = new Vec3(-4.5, 0.5, z);
                float occlusion = occlusion(simulation, HALF_WALL, listener, SOURCE);
                boolean clear = !blocked(listener, SOURCE);
                System.out.printf(Locale.ROOT, "[diffraction] listener z=%5.1f centre %s occlusion=%.3f%n",
                        z, clear ? "clear  " : "blocked", occlusion);
                if (z >= 24) assertEquals(1, occlusion, 1e-6, "whole sphere in view at z=" + z);
                assertTrue(occlusion <= previous + 1e-6, "fades monotonically, z=" + z);
                if (wasClear && !clear) {
                    // No step where the centre line crosses the edge: half a block either side.
                    assertTrue(previous - occlusion < 0.25, "continuous across the shadow boundary at z=" + z);
                    atBoundary = occlusion;
                }
                partial |= occlusion > 0.05 && occlusion < 0.95;
                wasClear = clear;
                previous = occlusion;
            }
            assertTrue(partial, "the shadow edge is soft");
            assertTrue(atBoundary > 0.2 && atBoundary < 0.8, "about half the sphere is visible at the boundary: " + atBoundary);
            assertEquals(0, previous, 1e-6, "deep shadow is fully occluded");
        }
    }

    /**
     * What the listener hears, per band, from occlusion and transmission alone (distance and air
     * absorption left out): it must fall without steps from full level to the wall's transmission.
     * Fine steps tell a steep fade (the path through the wall's corner lengthens quickly past the
     * edge) from a jump, which stays the same size however finely the walk is sampled.
     */
    @Test
    void theHeardLevelFadesWithoutStepsInEveryBand() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1)) {
            float[] previous = null;
            for (double z = 30; z >= -10; z -= 0.125) {
                Vec3 listener = new Vec3(-4.5, 0.5, z);
                var direct = simulate(simulation, HALF_WALL, listener, SOURCE).direct;
                float[] heard = heard(direct);
                System.out.printf(Locale.ROOT, "[diffraction] z=%5.1f occlusion=%.3f heard low %.3f mid %.3f high %.3f%n",
                        z, direct.occlusion, heard[0], heard[1], heard[2]);
                for (int band = 0; band < 3; band++) {
                    if (z >= 24) assertEquals(1, heard[band], 1e-6, "full level with the sphere in view, z=" + z);
                    if (previous == null) continue;
                    // Deeper in the shadow the path through the wall turns square and shortens a little.
                    assertTrue(heard[band] <= previous[band] * 1.06f, "falls, band " + band + " z=" + z);
                    double step = 20 * Math.log10(previous[band] / heard[band]);
                    assertTrue(step < 3, "no step over 3 dB per eighth of a block, band " + band + " z=" + z + ": " + step);
                }
                previous = heard;
            }
            float[] wall = AcousticMaterials.transmission(AcousticMaterials.STONE, 1);
            for (int band = 0; band < 3; band++) {
                assertEquals(wall[band], previous[band], wall[band] * 0.35, "deep shadow is the wall's transmission, band " + band);
            }
        }
    }

    /**
     * A cave full of one-block pillars, walked at about 4.3 blocks a second (1/16 block per 512-sample
     * block): with several pillars on the line, the sampled occlusion and the path through the
     * stone change quickly, but what the listener hears from one block to the next must not jump.
     */
    @Test
    void walkingThroughPillarsChangesTheLevelWithoutJumps() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        AcousticVoxelTrace.Lookup pillars = pos -> Math.floorMod(pos.getX(), 4) == 0
                && Math.floorMod(pos.getZ(), 4) == 0 && Math.abs(pos.getY()) <= 8 ? STONE : null;
        Vec3 source = new Vec3(2.5, 0.5, -14.5);
        try (var simulation = new SteamSimulation(44100, 1)) {
            for (double lane : new double[] {6.5, 5.5, 2.5}) {
                var gain = new DirectSoundGain(SteamRenderer.FRAME / 44100.0);
                float[] previous = null;
                double worst = 0;
                for (double x = -12; x <= 12; x += 1.0 / 16) {
                    var direct = simulate(simulation, pillars, new Vec3(x, 0.5, lane), source).direct;
                    float[] heard = heard(gain, direct);
                    for (int band = 0; previous != null && band < 3; band++) {
                        double step = Math.abs(20 * Math.log10(previous[band] / heard[band]));
                        worst = Math.max(worst, step);
                        assertTrue(step < 4, "lane " + lane + " x=" + x + " band " + band + ": " + step + " dB in one block");
                    }
                    previous = heard;
                }
                System.out.printf(Locale.ROOT, "[diffraction] pillars, lane %.1f: worst %.1f dB per block%n", lane, worst);
            }
        }
    }

    @Test
    void anOpeningNearTheListenerCountsAsWellAsOneNearTheSource() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        // Every point within 2 blocks of this source is hidden from the listener; the edge is
        // next to the listener, so only the listener-side sphere sees around it.
        Vec3 source = new Vec3(20.5, 0.5, -10.5), listener = new Vec3(-1.5, 0.5, 0.5);
        try (var simulation = new SteamSimulation(44100, 1)) {
            float occlusion = occlusion(simulation, HALF_WALL, listener, source);
            System.out.printf(Locale.ROOT, "[diffraction] edge near listener: occlusion=%.3f%n", occlusion);
            assertTrue(occlusion > 0.05, "listener-side samples see past the edge");
            withProperty(RADIUS, "0", () -> assertEquals(0, occlusion(simulation, HALF_WALL, listener, source), 1e-6,
                    "a single ray is blocked"));
        }
    }

    @Test
    void radiusZeroRestoresTheSingleOcclusionRay() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1)) {
            Vec3 listener = new Vec3(-4.5, 0.5, 5.5); // centre line blocked, near the edge
            assertTrue(blocked(listener, SOURCE));
            assertTrue(occlusion(simulation, HALF_WALL, listener, SOURCE) > 0.05);
            withProperty(RADIUS, "0", () -> assertEquals(0, occlusion(simulation, HALF_WALL, listener, SOURCE), 1e-6));
        }
    }

    /**
     * Prints the cost of one direct update (what the direct worker pays per radio, up to 125 times
     * a second while something moves) for each sample count. Asserts nothing about time.
     */
    @Test
    void benchmarkDirectUpdateCost() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        // A cave: solid rock except two rooms joined by a corridor; the source sits behind the
        // corner of the far room, the listener walks the corridor and the near room.
        AcousticVoxelTrace.Lookup cave = pos -> {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            boolean roomA = x >= 0 && x < 12 && y >= 0 && y < 6 && z >= 0 && z < 12;
            boolean corridor = x >= 12 && x < 40 && y >= 0 && y < 3 && z >= 5 && z < 8;
            boolean roomB = x >= 40 && x < 52 && y >= 0 && y < 6 && z >= -10 && z < 8;
            return roomA || corridor || roomB ? null : STONE;
        };
        Vec3 caveSource = new Vec3(46.5, 1.5, -7.5);
        Vec3[] caveListeners = {new Vec3(6.5, 1.6, 6.5), new Vec3(20.5, 1.6, 6.5), new Vec3(38.5, 1.6, 6.5), new Vec3(43.5, 1.6, 6.5)};
        // Open ground with one long wall: long rays through air, the worst case for the voxel walk.
        Vec3 openSource = new Vec3(30.5, 0.5, -20.5);
        Vec3[] openListeners = {new Vec3(-30.5, 0.5, 2.5), new Vec3(-20.5, 0.5, -10.5), new Vec3(-30.5, 0.5, 20.5), new Vec3(-5.5, 0.5, 3.5)};
        try (var simulation = new SteamSimulation(44100, 1)) {
            String[][] configs = {{"0", "16"}, {"2", "8"}, {"2", "16"}, {"2", "32"}};
            double[][] best = new double[configs.length][2];
            for (double[] row : best) java.util.Arrays.fill(row, Double.POSITIVE_INFINITY);
            // Round 0 only warms up the JIT; the best of the other rounds is reported.
            for (int round = 0; round < 4; round++) {
                for (int c = 0; c < configs.length; c++) {
                    int index = c;
                    boolean measured = round > 0;
                    withProperty(RADIUS, configs[index][0], () -> withProperty(SAMPLES, configs[index][1], () -> {
                        double inCave = microseconds(simulation, cave, caveListeners, caveSource);
                        double inOpen = microseconds(simulation, HALF_WALL, openListeners, openSource);
                        if (measured) {
                            best[index][0] = Math.min(best[index][0], inCave);
                            best[index][1] = Math.min(best[index][1], inOpen);
                        }
                    }));
                }
            }
            for (int c = 0; c < configs.length; c++) {
                System.out.printf(Locale.ROOT, "[diffraction-bench] %s: cave %.1f us, open %.1f us per direct update%n",
                        configs[c][0].equals("0") ? "single ray        " : "radius 2, " + configs[c][1] + " samples",
                        best[c][0], best[c][1]);
            }
        }
    }

    private static double microseconds(SteamSimulation simulation, AcousticVoxelTrace.Lookup lookup, Vec3[] listeners, Vec3 source) {
        int warmup = 200, runs = 2000;
        long start = 0;
        for (int i = 0; i < warmup + runs; i++) {
            if (i == warmup) start = System.nanoTime();
            simulate(simulation, lookup, listeners[i % listeners.length], source);
        }
        return (System.nanoTime() - start) / 1000.0 / runs;
    }

    private static float[] heard(SteamAudio.DirectParams direct) {
        return heard(new DirectSoundGain(), direct);
    }

    private static float[] heard(DirectSoundGain gain, SteamAudio.DirectParams direct) {
        var shading = new SteamAudio.DirectParams();
        shading.flags = 8 | 16;
        shading.transmissionType = direct.transmissionType;
        shading.occlusion = direct.occlusion;
        shading.transmission = direct.transmission.clone();
        float level = gain.prepare(shading);
        float[] heard = new float[3];
        for (int band = 0; band < 3; band++) heard[band] = level * gain.equalization().air[band];
        return heard;
    }

    private static boolean blocked(Vec3 listener, Vec3 source) {
        return AcousticVoxelTrace.cast(listener, source, HALF_WALL).kind() != AcousticRay.Kind.MISS;
    }

    private static float occlusion(SteamSimulation simulation, AcousticVoxelTrace.Lookup lookup, Vec3 listener, Vec3 source) {
        return simulate(simulation, lookup, listener, source).direct.occlusion;
    }

    private static SteamAudio.SimulationOutputs simulate(SteamSimulation simulation, AcousticVoxelTrace.Lookup lookup,
            Vec3 listener, Vec3 source) {
        return simulation.simulate((from, to) -> AcousticVoxelTrace.cast(from, to, lookup), listener, source, 1, 0);
    }

    private static void withProperty(String name, String value, Runnable body) {
        String previous = System.getProperty(name);
        System.setProperty(name, value);
        try { body.run(); }
        finally {
            if (previous == null) System.clearProperty(name);
            else System.setProperty(name, previous);
        }
    }
}
