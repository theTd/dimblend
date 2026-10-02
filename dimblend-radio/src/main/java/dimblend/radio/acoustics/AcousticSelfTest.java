package dimblend.radio.acoustics;

import java.util.Arrays;
import net.minecraft.world.phys.Vec3;

/**
 * In-game wet-path smoke test: a synthetic 16m room, GPU simulation, unit-impulse render.
 * Answers "is the wet pipeline healthy in this JVM" independently of any session state.
 * Triggered by live probes; logs to the radio logger at INFO.
 */
public final class AcousticSelfTest {
    public static void runAsync() {
        Thread thread = new Thread(AcousticSelfTest::run, "Radio acoustic self-test");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        try {
            float[] vertices = {-8,-8,-8, 8,-8,-8, 8,8,-8, -8,8,-8,
                    -8,-8,8, 8,-8,8, 8,8,8, -8,8,8};
            int[] triangles = {0,2,1,0,3,2, 4,5,6,4,6,7, 0,1,5,0,5,4,
                    3,7,6,3,6,2, 0,4,7,0,7,3, 1,2,6,1,6,5};
            int[] materials = new int[triangles.length / 3];
            Arrays.fill(materials, AcousticMaterials.STONE);
            try (var simulation = new SteamSimulation(44100, 2, true);
                    var renderer = new SteamRenderer(simulation.context(), 44100)) {
                var outputs = simulation.simulateGpu(
                        new AcousticMesh.Data(vertices, triangles, materials, Vec3.ZERO),
                        Vec3.ZERO, new Vec3(4, 0, 0), 64, 128);
                var muted = new SteamAudio.DirectParams();
                muted.distance = 0;
                double wet = 0;
                for (int block = 0; block < 30; block++) {
                    float[] input = new float[SteamRenderer.FRAME];
                    if (block == 0) input[0] = 1;
                    float[][] output = renderer.render(input, muted, outputs.reflections,
                            new Vec3(4, 0, 0), new SteamAudio.Space(), false, 1f);
                    for (float[] channel : output) for (float sample : channel) wet += sample * (double) sample;
                }
                dimblend.radio.DimBlendRadio.LOGGER.info("[radio] acoustic self-test: wet={} channels={} irSize={}",
                        String.format("%.5f", wet), outputs.reflections.channels, outputs.reflections.irSize);
            }
        } catch (Throwable error) {
            dimblend.radio.DimBlendRadio.LOGGER.warn("[radio] acoustic self-test failed", error);
        }
    }

    private AcousticSelfTest() { }

    /**
     * Same smoke test but with the live mirrored world mesh around the given listener/source
     * (the session's actual geometry source). Splits "session plumbing" from "decoded mesh".
     */
    public static void runWorldMeshAsync(Vec3 listener, Vec3 source) {
        Thread thread = new Thread(() -> runWorldMesh(listener, source), "Radio acoustic world-mesh test");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runWorldMesh(Vec3 listener, Vec3 source) {
        try {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null) throw new IllegalStateException("no level");
            var bounds = new net.minecraft.world.phys.AABB(listener, source).inflate(24);
            var coverage = dimblend.radio.acoustics.terrain.SectionGeometryCache.presentSections(bounds,
                    level.getMinSection(), level.getMaxSection());
            Vec3 origin = new Vec3(Math.floor(listener.x / 16) * 16, Math.floor(listener.y / 16) * 16,
                    Math.floor(listener.z / 16) * 16);
            var mesh = new AcousticMesh(origin);
            mesh.appendSections(coverage.sections(), net.minecraft.core.BlockPos.containing(source));
            AcousticMesh.Data data = mesh.data();
            try (var simulation = new SteamSimulation(44100, 2, true);
                    var renderer = new SteamRenderer(simulation.context(), 44100)) {
                var outputs = simulation.simulateGpu(data, listener, source, 64, 128);
                var muted = new SteamAudio.DirectParams();
                muted.distance = 0;
                double wet = 0;
                for (int block = 0; block < 30; block++) {
                    float[] input = new float[SteamRenderer.FRAME];
                    if (block == 0) input[0] = 1;
                    float[][] output = renderer.render(input, muted, outputs.reflections,
                            source.subtract(listener), new SteamAudio.Space(), false, 1f);
                    for (float[] channel : output) for (float sample : channel) wet += sample * (double) sample;
                }
                dimblend.radio.DimBlendRadio.LOGGER.info(
                        "[radio] world-mesh test: wet={} tris={} sections={} origin={}",
                        String.format("%.5f", wet), data.triangles().length / 3, coverage.sections().size(), origin);
            }
        } catch (Throwable error) {
            dimblend.radio.DimBlendRadio.LOGGER.warn("[radio] world-mesh test failed", error);
        }
    }

    /**
     * Dumps the assembled world mesh (same assembly as the session's reflection geometry) to a
     * binary file for offline reproduction: origin xyz (doubles), vertices (floats), triangles
     * and materials (ints).
     */
    public static void dumpWorldMesh(Vec3 listener, Vec3 source, java.nio.file.Path out) {
        try {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level == null) throw new IllegalStateException("no level");
            var bounds = new net.minecraft.world.phys.AABB(listener, source).inflate(24);
            var coverage = dimblend.radio.acoustics.terrain.SectionGeometryCache.presentSections(bounds,
                    level.getMinSection(), level.getMaxSection());
            Vec3 origin = new Vec3(Math.floor(listener.x / 16) * 16, Math.floor(listener.y / 16) * 16,
                    Math.floor(listener.z / 16) * 16);
            var mesh = new AcousticMesh(origin);
            mesh.appendSections(coverage.sections(), net.minecraft.core.BlockPos.containing(source));
            AcousticMesh.Data data = mesh.data();
            try (var stream = new java.io.DataOutputStream(java.nio.file.Files.newOutputStream(out))) {
                stream.writeDouble(origin.x);
                stream.writeDouble(origin.y);
                stream.writeDouble(origin.z);
                stream.writeInt(data.vertices().length);
                for (float v : data.vertices()) stream.writeFloat(v);
                stream.writeInt(data.triangles().length);
                for (int t : data.triangles()) stream.writeInt(t);
                stream.writeInt(data.materials().length);
                for (int m : data.materials()) stream.writeInt(m);
            }
            dimblend.radio.DimBlendRadio.LOGGER.info("[radio] world mesh dumped to {} ({} tris)", out,
                    data.triangles().length / 3);
        } catch (Throwable error) {
            dimblend.radio.DimBlendRadio.LOGGER.warn("[radio] world mesh dump failed", error);
        }
    }
}
