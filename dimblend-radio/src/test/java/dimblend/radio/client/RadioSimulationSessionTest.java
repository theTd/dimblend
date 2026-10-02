package dimblend.radio.client;

import dimblend.radio.acoustics.SteamRenderer;
import dimblend.radio.acoustics.SteamSimulation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RadioSimulationSessionTest {
    @Test void gpuFailureWaitsForAudioAndWithdrawsAllNativeState() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var session = new RadioSimulationSession(new AudioFormat(44100, 16, 1, true, false));
        try {
            session.setView(new Vec3(4,0,0), Vec3.ZERO, new Vec3(0,0,-1), new Vec3(0,1,0), true);
            SteamSimulation old = awaitEngine(session);
            assertTrue(old.gpu());
            assertNotNull(read(session, "renderer"), "Renderer must be prepared before the engine is published");
            var started = new CountDownLatch(1);
            CompletableFuture<Object> replacement;
            synchronized (session) {
                replacement = CompletableFuture.supplyAsync(() -> {
                    started.countDown();
                    failSession(session);
                    return null;
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> replacement.get(100, TimeUnit.MILLISECONDS));
                assertNotNull(old.context(), "Native source must remain alive while an audio frame holds the monitor");
                assertSame(old, read(session, "reflectionEngine"));
            }
            replacement.get(10, TimeUnit.SECONDS);
            drainWorker("REFLECTIONS");
            drainWorker("DIRECT");
            assertNull(old.context(), "Retired native context must be released");
            assertNull(read(session, "reflectionOutputs"));
            assertNull(read(session, "reflectionEngine"), "GPU failure must not install a CPU simulator");
            assertNull(read(session, "directEngine"), "GPU failure must stop direct simulation too");
            assertNull(read(session, "renderer"));
            assertFalse(session.active());
            session.close();
            drainReflectionWorker();
            assertNull(read(session, "renderer"));
        } finally { session.close(); }
    }

    @Test void repeatedInvalidGpuOutputDisablesAcousticsInsteadOfFallingBackToCpu() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var session = new RadioSimulationSession(new AudioFormat(44100, 16, 1, true, false));
        try {
            session.setView(new Vec3(4,0,0), Vec3.ZERO, new Vec3(0,0,-1), new Vec3(0,1,0), true);
            awaitEngine(session);
            synchronized (session) {
                var renderer = (SteamRenderer) read(session, "renderer");
                renderer.resetReflections();
                renderer.resetReflections();
                assertEquals(false, read(session, "failed"));
                renderer.resetReflections();
                assertEquals(true, read(session, "failed"), "Repeated GPU failures must settle on panned playback");
            }
        } finally {
            session.close();
            drainReflectionWorker();
        }
    }

    @Test void gpuDisabledCreatesNoAcousticEnginesButStillPlaysDistanceOnlyPcm() throws Exception {
        String previous = System.getProperty("dimblend.radio.acoustic.gpu");
        System.setProperty("dimblend.radio.acoustic.gpu", "false");
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        try {
            session.setView(new Vec3(4,0,0), Vec3.ZERO, new Vec3(0,0,-1), new Vec3(0,1,0), true);
            drainReflectionWorker(); drainWorker("DIRECT");
            assertFalse(session.active());
            assertNull(read(session,"reflectionEngine"));
            assertNull(read(session,"directEngine"));
            assertNull(read(session,"renderer"));
            var output=session.process(constant(12000),false).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            short left=output.getShort(), right=output.getShort();
            assertTrue(right>0, "The fallback must keep playing");
            assertTrue(right>left, "The fallback must keep the source direction (source on the right)");
        } finally {
            session.close();
            if(previous==null) System.clearProperty("dimblend.radio.acoustic.gpu");
            else System.setProperty("dimblend.radio.acoustic.gpu",previous);
        }
    }

    @Test void unselectedRadiosArePannedWithoutCreatingNativeEngines() throws Exception {
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        try {
            session.setView(new Vec3(-4,0,0), Vec3.ZERO, new Vec3(0,0,-1), new Vec3(0,1,0), true, false);
            drainReflectionWorker();
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean) read(session,"initialized")).get(),
                    "Only simulated radios may create Steam Audio engines");
            var output=session.process(constant(12000),false).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            short left=output.getShort(), right=output.getShort();
            assertTrue(left>right && left>0, "An audible radio outside the simulated set must stay audible and directional");
        } finally { session.close(); }
    }

    @Test void pathChangesFadeAcrossOneBlockInsteadOfClicking() throws Exception {
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        try {
            Vec3 ahead = new Vec3(0,0,-1), up = new Vec3(0,1,0), source = new Vec3(0,0,-4);
            session.setView(source, Vec3.ZERO, ahead, up, true, false);
            float[] steady = left(session.process(constant(12000), false));
            session.setView(source, Vec3.ZERO, ahead, up, false, false);
            float[] fadeOut = left(session.process(constant(12000), false));
            float[] silent = left(session.process(constant(12000), false));
            session.setView(source, Vec3.ZERO, ahead, up, true, false);
            float[] fadeIn = left(session.process(constant(12000), false));
            float level = steady[SteamRenderer.FRAME - 1];
            assertTrue(level > 0.1f);
            assertEquals(level, fadeOut[0], level * 0.01f, "no step when the radio is paused or leaves range");
            assertEquals(0, fadeOut[SteamRenderer.FRAME - 1], level * 0.01f);
            for (float sample : silent) assertEquals(0, sample);
            assertEquals(0, fadeIn[0], level * 0.01f, "no step when it becomes audible again");
            assertEquals(level, fadeIn[SteamRenderer.FRAME - 1], level * 0.01f);
        } finally { session.close(); }
    }

    private static java.nio.ByteBuffer constant(int value) {
        var input=java.nio.ByteBuffer.allocateDirect(SteamRenderer.FRAME*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while(input.hasRemaining()) input.putShort((short)value);
        return input.flip();
    }

    private static float[] left(java.nio.ByteBuffer stereo) {
        stereo.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] left = new float[stereo.remaining() / 4];
        for (int i = 0; i < left.length; i++) {
            left[i] = stereo.getShort() / 32768f;
            stereo.getShort();
        }
        return left;
    }

    private static void drainReflectionWorker() throws Exception {
        drainWorker("REFLECTIONS");
    }

    private static void drainWorker(String name) throws Exception {
        var field = RadioSimulationSession.class.getDeclaredField(name);
        field.setAccessible(true);
        var worker = (java.util.concurrent.ExecutorService) field.get(null);
        worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    private static SteamSimulation awaitEngine(RadioSimulationSession session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            synchronized (session) {
                var engine = (SteamSimulation) read(session, "reflectionEngine");
                if (engine != null) return engine;
                assertEquals(false, read(session, "failed"));
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Reflection initialization timed out");
    }

    private static Object read(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void failSession(Object owner) {
        try {
            var method = owner.getClass().getDeclaredMethod("fail", Throwable.class);
            method.setAccessible(true);
            method.invoke(owner, new IllegalStateException("Injected GPU failure"));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
