package dimblend.radio.client;

import dimblend.radio.acoustics.AcousticMesh;
import dimblend.radio.acoustics.ReflectionGeometry;
import dimblend.radio.acoustics.SteamAudio;
import dimblend.radio.acoustics.SteamRenderer;
import dimblend.radio.acoustics.SteamSimulation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
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
            SharedReflectionSimulator.Membership membership = awaitMembership(session);
            SteamSimulation old = membership.simulation();
            assertTrue(old.gpu());
            assertNotNull(read(session, "renderer"), "Renderer must be prepared before the engine is published");
            var started = new CountDownLatch(1);
            CompletableFuture<Object> replacement;
            synchronized (session) {
                replacement = CompletableFuture.supplyAsync(() -> {
                    started.countDown();
                    session.fail(new IllegalStateException("Injected GPU failure"));
                    return null;
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> replacement.get(100, TimeUnit.MILLISECONDS));
                assertNotNull(old.context(), "Native source must remain alive while an audio frame holds the monitor");
                assertSame(membership, session.reflections());
            }
            replacement.get(10, TimeUnit.SECONDS);
            drainReflectionWorker();
            drainWorker("DIRECT");
            assertTrue(membership.source().closed(), "The failed radio's source must leave the shared simulator");
            assertNull(old.context(), "The last radio leaving must release the shared native context");
            assertNull(read(session, "reflectionOutputs"));
            assertNull(session.reflections(), "GPU failure must not install a CPU simulator");
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
            awaitMembership(session);
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
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false), 0);
        try {
            session.setView(new Vec3(4,0,0), Vec3.ZERO, new Vec3(0,0,-1), new Vec3(0,1,0), true);
            drainReflectionWorker(); drainWorker("DIRECT");
            assertFalse(session.active());
            assertNull(session.reflections());
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

    @Test void radiosOfOneRateShareOneSimulatorAndEachHearsItsOwnResponse() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        Vec3 ahead = new Vec3(0,0,-1), up = new Vec3(0,1,0);
        var first = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        var second = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        var other = new RadioSimulationSession(new AudioFormat(48000,16,1,true,false));
        try {
            first.setView(new Vec3(4,0,0), Vec3.ZERO, ahead, up, true);
            second.setView(new Vec3(-3,0,2), Vec3.ZERO, ahead, up, true);
            other.setView(new Vec3(0,0,5), Vec3.ZERO, ahead, up, true);
            var a = awaitMembership(first);
            var b = awaitMembership(second);
            var c = awaitMembership(other);
            assertSame(a.simulation(), b.simulation(), "radios of one sampling rate share one simulator");
            assertNotSame(a.source(), b.source());
            assertEquals(2, a.simulation().sourceCount());
            assertNotSame(a.simulation(), c.simulation(), "the simulator runs at its radios' rate");
            assertNotSame(read(first, "renderer"), read(second, "renderer"));

            SharedReflectionSimulator.simulate(List.of(first, second, other), new Room(), 1, System.nanoTime());
            drainReflectionWorker();
            var heardFirst = (SteamAudio.SimulationOutputs) read(first, "reflectionOutputs");
            var heardSecond = (SteamAudio.SimulationOutputs) read(second, "reflectionOutputs");
            assertNotNull(heardFirst, "one shared run answers every radio in it");
            assertNotNull(heardSecond);
            assertNotNull(read(other, "reflectionOutputs"));
            assertNotNull(heardFirst.reflections.ir);
            assertNotEquals(heardFirst.reflections.ir, heardSecond.reflections.ir, "each radio has its own IR");
            assertEquals(1L, read(first, "reflectionRevision"));

            first.close();
            drainReflectionWorker();
            assertTrue(a.source().closed());
            assertNotNull(b.simulation().context(), "the simulator stays while another radio uses it");
            assertEquals(1, b.simulation().sourceCount());
            second.close();
            drainReflectionWorker();
            assertNull(b.simulation().context(), "the last radio to leave closes it");
            assertNotNull(c.simulation().context());
        } finally {
            first.close();
            second.close();
            other.close();
            drainReflectionWorker();
        }
    }

    @Test void unselectedRadiosArePannedWithoutCreatingNativeEngines() throws Exception {
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false), 0);
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
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false), 0);
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

    @Test void lookaheadDelaysTheStreamButOrientationIsTakenAtPlayback() throws Exception {
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        try {
            Vec3 up = new Vec3(0,1,0), source = new Vec3(4,0,0);
            session.setView(source, Vec3.ZERO, new Vec3(0,0,-1), up, true, false);
            for (int block = 0; block < RadioSimulationSession.LOOKAHEAD_BLOCKS; block++) {
                for (float sample : left(session.process(constant(12000), false))) assertEquals(0, sample, "the look-ahead starts silent");
            }
            var facing = session.process(constant(12000), false).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            facing.position((SteamRenderer.FRAME - 1) * 4);
            short facingLeft = facing.getShort(), facingRight = facing.getShort();
            assertTrue(facingRight > facingLeft, "source on the right");
            // These blocks were submitted while facing north; turning around before they play must
            // swap the image at once, not a look-ahead later.
            session.setView(source, Vec3.ZERO, new Vec3(0,0,1), up, true, false);
            var turned = session.process(constant(12000), false).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            turned.position((SteamRenderer.FRAME - 1) * 4);
            short turnedLeft = turned.getShort(), turnedRight = turned.getShort();
            assertTrue(turnedLeft > turnedRight, "after turning around the source is on the left");
        } finally { session.close(); }
    }

    @Test void endOfInputPlaysOutTheLookaheadInOrder() throws Exception {
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        try {
            session.setView(new Vec3(0,0,-4), Vec3.ZERO, new Vec3(0,0,-1), new Vec3(0,1,0), true, false);
            int[] levels = {3000, 6000, 9000, 12000};
            List<Float> played = new ArrayList<>();
            for (int block = 0; block < levels.length; block++) {
                float[] out = left(session.process(constant(levels[block]), block == levels.length - 1));
                played.add(out[SteamRenderer.FRAME - 1]);
            }
            assertTrue(session.hasTail(), "blocks prepared ahead are still to be played");
            int guard = 0;
            while (session.hasTail() && guard++ < 10) {
                float[] out = left(session.process(java.nio.ByteBuffer.allocateDirect(0), true));
                if (out.length > 0) played.add(out[SteamRenderer.FRAME - 1]);
            }
            assertFalse(session.hasTail());
            List<Float> audible = played.stream().filter(level -> level > 0).toList();
            assertEquals(levels.length, audible.size(), "every block plays exactly once: " + played);
            for (int i = 1; i < audible.size(); i++) {
                assertEquals(audible.get(0) * levels[i] / levels[0], audible.get(i), audible.get(0) * 0.01f, "in order: " + audible);
            }
        } finally { session.close(); }
    }

    @Test void playbackDoesNotWaitForTheDspStageOnceBlocksAreStaged() throws Exception {
        var session = new RadioSimulationSession(new AudioFormat(44100,16,1,true,false));
        try {
            session.setView(new Vec3(0,0,-4), Vec3.ZERO, new Vec3(0,0,-1), new Vec3(0,1,0), true, false);
            for (int block = 0; block < RadioSimulationSession.LOOKAHEAD_BLOCKS; block++) session.process(constant(12000), false);
            var staged = (java.util.Queue<?>) read(session, "staged");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (staged.size() < RadioSimulationSession.LOOKAHEAD_BLOCKS && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(RadioSimulationSession.LOOKAHEAD_BLOCKS, staged.size(), "the DSP thread stages submitted blocks");
            synchronized (session) {
                // The position stage is busy (this monitor); the sound thread still plays what is staged.
                var played = CompletableFuture.supplyAsync(() -> left(session.process(constant(12000), false)));
                float[] out = played.get(5, TimeUnit.SECONDS);
                assertTrue(out[SteamRenderer.FRAME - 1] > 0.1f);
            }
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
        SharedReflectionSimulator.WORKER.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    private static void drainWorker(String name) throws Exception {
        var field = RadioSimulationSession.class.getDeclaredField(name);
        field.setAccessible(true);
        var worker = (java.util.concurrent.ExecutorService) field.get(null);
        worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    private static SharedReflectionSimulator.Membership awaitMembership(RadioSimulationSession session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            synchronized (session) {
                var membership = session.reflections();
                if (membership != null) return membership;
                assertEquals(false, read(session, "failed"));
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Reflection initialization timed out");
    }

    /** A closed 16-block room around the origin, nothing moving in it. */
    private static final class Room implements ReflectionGeometry {
        @Override public long terrainSection(long key) { return UNCAPTURED; }
        @Override public int minSection() { return -4; }
        @Override public int maxSection() { return 20; }
        @Override public Set<BlockPos> emitters() { return Set.of(); }
        @Override public List<? extends Body> bodies() { return List.of(); }

        @Override public AcousticMesh.Data terrainMesh(AABB bounds, Vec3 origin, AcousticMesh.Workspace workspace) {
            float[] vertices = {-8,-8,-8,8,-8,-8,8,8,-8,-8,8,-8,-8,-8,8,8,-8,8,8,8,8,-8,8,8};
            for (int i = 0; i < vertices.length; i += 3) {
                vertices[i] -= (float) origin.x;
                vertices[i + 1] -= (float) origin.y;
                vertices[i + 2] -= (float) origin.z;
            }
            int[] triangles = {0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,3,7,6,3,6,2,0,4,7,0,7,3,1,2,6,1,6,5};
            int[] materials = new int[12];
            java.util.Arrays.fill(materials, 4);
            return new AcousticMesh.Data(vertices, triangles, materials, origin);
        }
    }

    private static Object read(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
