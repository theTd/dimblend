package dimblend.radio.acoustics;

import java.nio.file.Path;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Replays a dumped live world mesh (build/reverb-validation/worldmesh.bin) through the GPU path. */
class SteamWorldMeshTest {
    @Test
    void dumpedWorldMeshWet() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        Path file = Path.of("../../build/reverb-validation/worldmesh.bin");
        assumeTrue(java.nio.file.Files.exists(file), "no dumped mesh");
        Vec3 origin;
        float[] vertices;
        int[] triangles, materials;
        try (var in = new java.io.DataInputStream(java.nio.file.Files.newInputStream(file))) {
            origin = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
            vertices = new float[in.readInt()];
            for (int i = 0; i < vertices.length; i++) vertices[i] = in.readFloat();
            triangles = new int[in.readInt()];
            for (int i = 0; i < triangles.length; i++) triangles[i] = in.readInt();
            materials = new int[in.readInt()];
            for (int i = 0; i < materials.length; i++) materials[i] = in.readInt();
        }
        System.out.println("[worldmesh] tris=" + triangles.length / 3 + " verts=" + vertices.length / 3
                + " origin=" + origin);
        int bad = 0;
        for (int t : triangles) if (t < 0 || t >= vertices.length / 3) bad++;
        System.out.println("[worldmesh] out-of-range indices=" + bad);
        int nan = 0;
        for (float v : vertices) if (!Float.isFinite(v)) nan++;
        System.out.println("[worldmesh] non-finite verts=" + nan);
        // Brute-force CPU raycast: is the room actually enclosed in this mesh?
        Vec3 src = new Vec3(221980.5, 69.5, -8.5).subtract(origin);
        int hit = 0;
        double distSum = 0;
        int rays = 64;
        for (int i = 0; i < rays; i++) {
            double y = 1 - 2 * (i + 0.5) / rays;
            double r = Math.sqrt(1 - y * y);
            double angle = i * Math.PI * (3 - Math.sqrt(5));
            Vec3 dir = new Vec3(Math.cos(angle) * r, y, Math.sin(angle) * r);
            double d = raycast(vertices, triangles, src, dir);
            if (d > 0) { hit++; distSum += d; }
        }
        System.out.println("[worldmesh] rays hit=" + hit + "/" + rays
                + " avgDist=" + (hit == 0 ? -1 : distSum / hit));
        int near = 0;
        for (int i = 0; i < triangles.length; i += 3) {
            Vec3 a = vertex(vertices, triangles[i]);
            Vec3 b = vertex(vertices, triangles[i + 1]);
            Vec3 c = vertex(vertices, triangles[i + 2]);
            Vec3 center = a.add(b).add(c).scale(1.0 / 3);
            if (center.distanceTo(src) < 1.0 && near < 8) {
                near++;
                System.out.println("[worldmesh] near-tri m=" + materials[i / 3] + " a=" + a + " b=" + b + " c=" + c);
            }
        }
        for (Vec3 axis : new Vec3[] {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 1, 0),
                new Vec3(0, -1, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1)}) {
            double[] best = {-1, -1};
            for (int i = 0; i < triangles.length; i += 3) {
                double d = rayTri(vertices, triangles, i, src, axis);
                if (d > 0 && (best[0] < 0 || d < best[0])) { best[0] = d; best[1] = i; }
            }
            int i = (int) best[1];
            System.out.println("[worldmesh] ray " + axis + " -> dist=" + best[0]
                    + (i >= 0 ? " m=" + materials[i / 3] + " a=" + vertex(vertices, triangles[i])
                    + " b=" + vertex(vertices, triangles[i + 1]) + " c=" + vertex(vertices, triangles[i + 2]) : ""));
        }
        try (var simulation = new SteamSimulation(44100, 2, true);
                var renderer = new SteamRenderer(simulation.context(), 44100)) {
            var outputs = simulation.simulateGpu(new AcousticMesh.Data(vertices, triangles, materials, origin),
                    new Vec3(221981.5, 69.0, -8.5), new Vec3(221980.5, 69.5, -8.5), 64, 128);
            var muted = new SteamAudio.DirectParams();
            muted.distance = 0;
            double wet = 0;
            for (int block = 0; block < 30; block++) {
                float[] input = new float[SteamRenderer.FRAME];
                if (block == 0) input[0] = 1;
                float[][] output = renderer.render(input, muted, outputs.reflections, new Vec3(1, 0.5, 0),
                        new SteamAudio.Space(), false, 1f);
                for (float[] channel : output) for (float sample : channel) wet += sample * (double) sample;
            }
            System.out.println("[worldmesh] wet=" + wet);
        }
    }

    private static double rayTri(float[] vertices, int[] triangles, int i, Vec3 from, Vec3 dir) {
        Vec3 a = vertex(vertices, triangles[i]);
        Vec3 ab = vertex(vertices, triangles[i + 1]).subtract(a);
        Vec3 ac = vertex(vertices, triangles[i + 2]).subtract(a);
        Vec3 cross = dir.cross(ac);
        double det = ab.dot(cross);
        if (Math.abs(det) < 1e-9) return -1;
        Vec3 delta = from.subtract(a);
        double u = delta.dot(cross) / det;
        Vec3 q = delta.cross(ab);
        double v = dir.dot(q) / det;
        double t = ac.dot(q) / det;
        return u >= -1e-6 && v >= -1e-6 && u + v <= 1.000001 && t > 0.001 ? t : -1;
    }

    private static double raycast(float[] vertices, int[] triangles, Vec3 from, Vec3 dir) {
        double nearest = -1;
        for (int i = 0; i < triangles.length; i += 3) {
            Vec3 a = vertex(vertices, triangles[i]);
            Vec3 ab = vertex(vertices, triangles[i + 1]).subtract(a);
            Vec3 ac = vertex(vertices, triangles[i + 2]).subtract(a);
            Vec3 cross = dir.cross(ac);
            double det = ab.dot(cross);
            if (Math.abs(det) < 1e-9) continue;
            Vec3 delta = from.subtract(a);
            double u = delta.dot(cross) / det;
            Vec3 q = delta.cross(ab);
            double v = dir.dot(q) / det;
            double t = ac.dot(q) / det;
            if (u >= -1e-6 && v >= -1e-6 && u + v <= 1.000001 && t > 0.001) {
                if (nearest < 0 || t < nearest) nearest = t;
            }
        }
        return nearest;
    }

    private static Vec3 vertex(float[] vertices, int index) {
        return new Vec3(vertices[index * 3], vertices[index * 3 + 1], vertices[index * 3 + 2]);
    }
}
