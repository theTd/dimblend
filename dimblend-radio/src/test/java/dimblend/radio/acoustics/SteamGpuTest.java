package dimblend.radio.acoustics;

import com.sun.jna.ptr.PointerByReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SteamGpuTest {
    @Test
    void roomMeshProducesAudibleDelayedReflections() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        float[] vertices = {-8,-8,-8, 8,-8,-8, 8,8,-8, -8,8,-8,
                -8,-8,8, 8,-8,8, 8,8,8, -8,8,8};
        int[] triangles = {0,2,1,0,3,2, 4,5,6,4,6,7, 0,1,5,0,5,4,
                3,7,6,3,6,2, 0,4,7,0,7,3, 1,2,6,1,6,5};
        int[] materials = new int[triangles.length / 3];
        java.util.Arrays.fill(materials, 4);
        long start = System.nanoTime();
        try (var simulation = new SteamSimulation(44100, 3, true)) {
            long initialized = System.nanoTime();
            var response = simulation.simulateGpu(new AcousticMesh.Data(vertices, triangles, materials, net.minecraft.world.phys.Vec3.ZERO),
                    net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(4, 0, 0), 64, 128);
            System.out.println("GPU setup ms=" + (initialized - start) / 1_000_000 + ", mesh/simulation ms=" + (System.nanoTime() - initialized) / 1_000_000);
            assertEquals(1, response.direct.occlusion, 0.001);
            assertNotNull(response.reflections.ir);
            assertEquals(4, response.reflections.channels);
            try (var renderer = new SteamRenderer(simulation.context(), 44100)) {
                var muted = new SteamAudio.DirectParams();
                muted.distance = 0;
                var orientation = new SteamAudio.Space();
                double energy = 0;
                int first = -1;
                for (int block = 0; block < 60; block++) {
                    float[] input = new float[SteamRenderer.FRAME];
                    if (block == 0) input[0] = 1;
                    var output = renderer.render(input, muted, response.reflections,
                            new net.minecraft.world.phys.Vec3(4, 0, 0), orientation, false, 1f);
                    for (int i = 0; i < input.length; i++) for (float[] channel : output) {
                        energy += channel[i] * channel[i];
                        if (first < 0 && Math.abs(channel[i]) > 1e-6) first = block * input.length + i;
                    }
                }
                assertTrue(energy > 1e-6, "Room must produce audible reflection energy: " + energy);
                assertTrue(first > 441, "Wall echoes must be delayed: " + first);
            }
        }
    }

    @Test
    void inPlaceReuploadKeepsWetHealthyAcrossGeometryChanges() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var room = new AcousticMesh.Data(new float[] {-8,-8,-8, 8,-8,-8, 8,8,-8, -8,8,-8,
                -8,-8,8, 8,-8,8, 8,8,8, -8,8,8},
                new int[] {0,2,1,0,3,2, 4,5,6,4,6,7, 0,1,5,0,5,4,
                        3,7,6,3,6,2, 0,4,7,0,7,3, 1,2,6,1,6,5},
                java.util.stream.IntStream.range(0, 12).map(i -> 4).toArray(), net.minecraft.world.phys.Vec3.ZERO);
        var wall = new AcousticMesh.Data(new float[] {2,-8,-8, 2,8,-8, 2,8,8, 2,-8,8},
                new int[] {0,1,2,0,2,3}, new int[] {4,4}, net.minecraft.world.phys.Vec3.ZERO);
        var empty = new AcousticMesh.Data(new float[0], new int[0], new int[0], net.minecraft.world.phys.Vec3.ZERO);
        // The session's exact lifecycle: one engine, one renderer, in-place re-simulation.
        try (var simulation = new SteamSimulation(44100, 3, true);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            double wet1 = wet(renderer, simulation.simulateGpu(room, net.minecraft.world.phys.Vec3.ZERO,
                    new net.minecraft.world.phys.Vec3(4, 0, 0), 64, 128).reflections);
            assertTrue(wet1 > 0.01, "room must produce wet energy: " + wet1);
            double wet2 = wet(renderer, simulation.simulateGpu(room, net.minecraft.world.phys.Vec3.ZERO,
                    new net.minecraft.world.phys.Vec3(4, 0, 0), 64, 128).reflections);
            assertTrue(wet2 > wet1 * 0.5, "unchanged geometry re-run: " + wet1 + " -> " + wet2);
            var blocked = simulation.simulateGpu(wall, net.minecraft.world.phys.Vec3.ZERO,
                    new net.minecraft.world.phys.Vec3(4, 0, 0), 64, 128);
            assertEquals(0, blocked.direct.occlusion, 0.001);
            assertEquals(0.25, blocked.direct.distance, 0.01);
            double wet3 = 0;
            // The simulator temporally smooths the reflection field across runs: after the wall
            // interlude drained it, the room needs a few runs to re-accumulate.
            for (int i = 0; i < 6; i++) {
                wet3 = wet(renderer, simulation.simulateGpu(room, net.minecraft.world.phys.Vec3.ZERO,
                        new net.minecraft.world.phys.Vec3(4, 0, 0), 64, 128).reflections);
            }
            assertTrue(wet3 > wet1 * 0.5, "wet must survive in-place re-upload: " + wet1 + " -> " + wet3);
            var cleared = simulation.simulateGpu(empty, net.minecraft.world.phys.Vec3.ZERO,
                    new net.minecraft.world.phys.Vec3(4, 0, 0), 64, 128);
            assertNull(cleared.reflections.ir, "empty scene must skip reflections");
            assertEquals(1, cleared.direct.occlusion, 0.001);
            double wet4 = 0;
            for (int i = 0; i < 6; i++) {
                wet4 = wet(renderer, simulation.simulateGpu(room, net.minecraft.world.phys.Vec3.ZERO,
                        new net.minecraft.world.phys.Vec3(4, 0, 0), 64, 128).reflections);
            }
            assertTrue(wet4 > wet1 * 0.5, "wet must recover after the empty scene: " + wet4);
        }
    }

    private static double wet(SteamRenderer renderer, SteamAudio.ReflectionParams impulse) {
        var muted = new SteamAudio.DirectParams();
        muted.distance = 0;
        double energy = 0;
        for (int block = 0; block < 30; block++) {
            float[] input = new float[SteamRenderer.FRAME];
            if (block == 0) input[0] = 1;
            float[][] output = renderer.render(input, muted, impulse, new net.minecraft.world.phys.Vec3(4, 0, 0),
                    new SteamAudio.Space(), false, 1f);
            for (float[] channel : output) for (float sample : channel) energy += sample * (double) sample;
        }
        return energy;
    }
    @Test
    void enumerateAndCreateGpuRayTracer() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var api = SteamGpu.api();
        var context = new PointerByReference();
        var list = new PointerByReference();
        var device = new PointerByReference();
        var rays = new PointerByReference();
        SteamAudio.check(api.iplContextCreate(new SteamAudio.ContextSettings(), context), "GPU context");
        try {
            SteamAudio.check(api.iplOpenCLDeviceListCreate(context.getValue(), new SteamGpu.DeviceSettings(), list), "OpenCL enumeration");
            int count = api.iplOpenCLDeviceListGetNumDevices(list.getValue());
            assertTrue(count > 0, "No compatible OpenCL GPU devices");
            var desc = new SteamGpu.DeviceDesc();
            api.iplOpenCLDeviceListGetDeviceDesc(list.getValue(), 0, desc);
            System.out.println("GPU acoustic device: " + desc.name.getString(0));
            SteamAudio.check(api.iplOpenCLDeviceCreate(context.getValue(), list.getValue(), 0, device), "OpenCL device");
            SteamAudio.check(api.iplRadeonRaysDeviceCreate(device.getValue(), null, rays), "GPU ray tracer");
        } finally {
            if (rays.getValue() != null) api.iplRadeonRaysDeviceRelease(rays);
            if (device.getValue() != null) api.iplOpenCLDeviceRelease(device);
            if (list.getValue() != null) api.iplOpenCLDeviceListRelease(list);
            api.iplContextRelease(context);
        }
    }
}
