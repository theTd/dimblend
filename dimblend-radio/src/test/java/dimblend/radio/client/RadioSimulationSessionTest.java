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
                assertEquals(true, read(session, "failed"), "Repeated GPU failures must settle on distance-only playback");
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
            var input=java.nio.ByteBuffer.allocateDirect(SteamRenderer.FRAME*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            while(input.hasRemaining()) input.putShort((short)12000);
            var output=session.process(input.flip(),false).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            assertTrue(output.getShort()>0);
        } finally {
            session.close();
            if(previous==null) System.clearProperty("dimblend.radio.acoustic.gpu");
            else System.setProperty("dimblend.radio.acoustic.gpu",previous);
        }
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
