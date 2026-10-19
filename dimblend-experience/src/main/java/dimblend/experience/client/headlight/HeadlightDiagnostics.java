package dimblend.experience.client.headlight;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import java.nio.FloatBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/** Opt-in (-Ddimblend.headlight.diag=true) throttled log of fog-pass state and a fog-target readback. */
final class HeadlightDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("dimblend.headlight.diag");
    private static final boolean READBACK = ENABLED && Boolean.getBoolean("dimblend.headlight.diag.readback");
    private static long next;
    private static boolean missingWarned;

    // Loaded only in diagnostic mode. All query access is on the render thread.
    private static final class Timing {
        private static final GpuPass SURFACE = new GpuPass();
        private static final GpuPass FOG = new GpuPass();
        private static long frame, nextFile, nextLog;
        private static int beams;
        private static boolean writeWarned;
        private static Path file;
    }

    private static final class GpuPass {
        private final int[] queries = new int[8];
        private final boolean[] pending = new boolean[8];
        private int cursor, active = -1;
        private long samples;
        private double sum, max, latest;

        private void poll() {
            for (int i = 0; i < queries.length; i++) {
                if (!pending[i] || GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
                latest = GL33.glGetQueryObjectui64(queries[i], GL15.GL_QUERY_RESULT) / 1_000_000.0;
                sum += latest;
                max = Math.max(max, latest);
                samples++;
                pending[i] = false;
            }
        }

        private void begin() {
            // Never wait for a busy slot, and don't interfere with another mod's timer.
            if (pending[cursor] || GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) != 0) return;
            if (queries[cursor] == 0) queries[cursor] = GL15.glGenQueries();
            active = cursor;
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[active]);
        }

        private void end() {
            if (active < 0) return;
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            pending[active] = true;
            cursor = (active + 1) % queries.length;
            active = -1;
        }

        private double average() { return samples == 0 ? 0 : sum / samples; }
        private void clearWindow() { samples = 0; sum = 0; max = 0; }
        private void close() {
            end();
            for (int i = 0; i < queries.length; i++) {
                if (queries[i] != 0) GL15.glDeleteQueries(queries[i]);
                queries[i] = 0;
                pending[i] = false;
            }
            latest = 0;
            clearWindow();
        }
    }

    static void frame() {
        if (!ENABLED) return;
        Timing.frame++;
        Timing.beams = 0;
        Timing.SURFACE.poll();
        Timing.FOG.poll();
    }

    static void beams(int count) { if (ENABLED) Timing.beams = count; }
    static void beginSurface() { if (ENABLED) Timing.SURFACE.begin(); }
    static void endSurface() { if (ENABLED) Timing.SURFACE.end(); }
    static void beginFog() { if (ENABLED) Timing.FOG.begin(); }
    static void endFog() { if (ENABLED) Timing.FOG.end(); }
    static void close() {
        if (!ENABLED) return;
        Timing.SURFACE.close();
        Timing.FOG.close();
    }

    static void publish() {
        if (!ENABLED) return;
        long now = System.currentTimeMillis();
        if (now >= Timing.nextFile) {
            Timing.nextFile = now + 500;
            if (Timing.file == null) Timing.file = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("agent_bridge/headlight_frame.json");
            String json = String.format(Locale.ROOT,
                    "{\"timestamp\":%d,\"frame\":%d,\"beams\":%d,\"surface_ms\":%.6f,\"fog_ms\":%.6f,"
                    + "\"surface_avg_ms\":%.6f,\"surface_max_ms\":%.6f,\"surface_samples\":%d,"
                    + "\"fog_avg_ms\":%.6f,\"fog_max_ms\":%.6f,\"fog_samples\":%d}%n",
                    now, Timing.frame, Timing.beams, Timing.SURFACE.latest, Timing.FOG.latest,
                    Timing.SURFACE.average(), Timing.SURFACE.max, Timing.SURFACE.samples,
                    Timing.FOG.average(), Timing.FOG.max, Timing.FOG.samples);
            try {
                Files.createDirectories(Timing.file.getParent());
                Path tmp = Timing.file.resolveSibling("headlight_frame.json.tmp");
                Files.writeString(tmp, json);
                try {
                    Files.move(tmp, Timing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tmp, Timing.file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException error) {
                if (!Timing.writeWarned) LOGGER.warn("[headlight-diag] Cannot write frame marker", error);
                Timing.writeWarned = true;
            }
        }
        if (now >= Timing.nextLog) {
            Timing.nextLog = now + 2000;
            LOGGER.info(String.format(Locale.ROOT,
                    "[headlight-diag] frame=%d beams=%d surface_ms avg/max=%.4f/%.4f fog_ms avg/max=%.4f/%.4f samples=%d/%d",
                    Timing.frame, Timing.beams, Timing.SURFACE.average(), Timing.SURFACE.max,
                    Timing.FOG.average(), Timing.FOG.max, Timing.SURFACE.samples, Timing.FOG.samples));
            Timing.SURFACE.clearWindow();
            Timing.FOG.clearWindow();
        }
    }

    /** Always on: a missing program (e.g. shader compile failure) otherwise disables fog silently. */
    static void missingProgram(boolean fog, boolean composite) {
        if (missingWarned) return;
        missingWarned = true;
        LOGGER.warn("Headlight fog disabled: program unavailable (fog={}, composite={}); see Veil shader errors above",
                fog ? "ok" : "missing", composite ? "ok" : "missing");
    }

    static boolean due() {
        if (!READBACK) return false;
        long now = System.currentTimeMillis();
        if (now < next) return false;
        next = now + 2000;
        return true;
    }

    /** Call with the fog target bound for reading; reads RGBA16F as floats (diagnostic only). */
    static void logFog(int beams, double density, int samples, int width, int height, int texture) {
        int error = GL11.glGetError();
        FloatBuffer pixels = MemoryUtil.memAllocFloat(width * height * 4);
        try {
            // Through the state cache so HeadlightRenderState restores the real binding.
            GlStateManager._bindTexture(texture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
            float max = 0;
            double sum = 0;
            int lit = 0;
            for (int i = 0; i < width * height; i++) {
                float r = pixels.get(i * 4);
                max = Math.max(max, r);
                sum += r;
                if (r > 0.02f) lit++;
            }
            int center = ((height / 2) * width + width / 2) * 4;
            LOGGER.info("Headlight fog diag: beams={} density={} samples={} target={}x{} glError(before)={} glError(after)={}"
                            + " maxR={} meanR={} litTexels(>0.02)={} centerRGBA=({}, {}, {}, {})",
                    beams, density, samples, width, height, error, GL11.glGetError(), max, sum / (width * height), lit,
                    pixels.get(center), pixels.get(center + 1), pixels.get(center + 2), pixels.get(center + 3));
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** Center pixel of the currently bound read framebuffer. */
    static float[] readCenter(int width, int height) {
        float[] rgba = new float[4];
        GL11.glReadPixels(width / 2, height / 2, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, rgba);
        return rgba;
    }

    static void logComposite(float[] before, float[] after) {
        LOGGER.info("Headlight composite diag: main center RGB before=({}, {}, {}) after=({}, {}, {}) glError={}",
                before[0], before[1], before[2], after[0], after[1], after[2], GL11.glGetError());
    }

    private HeadlightDiagnostics() {}
}
