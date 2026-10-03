package dimblend.radio.client;

import dimblend.radio.acoustics.SteamRenderer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dimblend.radio.client.RadioSimulationSessionTest.awaitMembership;
import static dimblend.radio.client.RadioSimulationSessionTest.constant;
import static dimblend.radio.client.RadioSimulationSessionTest.drainReflectionWorker;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AcousticRecordingTest {
    private static final Vec3 AHEAD = new Vec3(0, 0, -1), UP = new Vec3(0, 1, 0);
    @TempDir Path root;

    @AfterEach void stopLeftovers() throws Exception {
        AcousticRecording recording = AcousticRecording.active();
        if (recording != null) recording.stop().get(10, TimeUnit.SECONDS);
    }

    @Test void aRecordingHoldsWhatEachRadioPlayedAndHow() throws Exception {
        var session = new RadioSimulationSession(new AudioFormat(44100, 16, 1, true, false), 0);
        ByteArrayOutputStream played = new ByteArrayOutputStream();
        AcousticRecording recording = AcousticRecording.start(root, 10);
        assertSame(recording, AcousticRecording.active());
        assertThrows(IllegalStateException.class, () -> AcousticRecording.start(root, 10), "one recording at a time");
        try {
            session.setView(new Vec3(-4, 0, 0), Vec3.ZERO, AHEAD, UP, true, false);
            for (int block = 0; block < 3; block++) played.write(bytes(session.process(constant(12000), false)));
            session.setView(new Vec3(-4, 0, 0), Vec3.ZERO, AHEAD, UP, false, false);
            played.write(bytes(session.process(constant(12000), false)));
        } finally { session.close(); }
        AcousticRecording.Summary summary = recording.stop().get(10, TimeUnit.SECONDS);
        assertNull(AcousticRecording.active());
        assertEquals(recording.directory(), summary.directory());
        assertEquals(1, summary.radios());
        assertEquals(0, summary.dropped());
        assertNull(summary.failure());

        List<Map<String, String>> rows = csv(recording.directory().resolve("timeline.csv"));
        assertEquals(List.of("panned", "panned", "panned", "panned>silent"), rows.stream().map(row -> row.get("mode")).toList());
        for (int block = 0; block < rows.size(); block++) {
            Map<String, String> row = rows.get(block);
            assertEquals("1", row.get("radio"));
            assertEquals(Integer.toString(block), row.get("block"));
            assertEquals(block * SteamRenderer.FRAME / 44100.0, Double.parseDouble(row.get("audio_s")), 1e-4);
            assertEquals(4, Double.parseDouble(row.get("distance")), 1e-3);
            assertEquals("", row.get("reflections"), "a panned block has no rendered part");
            assertEquals("", row.get("echo_db"));
        }
        assertTrue(Double.parseDouble(rows.get(0).get("output_db")) > -30, "the played level is kept");

        short[] output = wav(recording.directory().resolve("radio-1-output.wav"), 44100);
        assertArrayEquals(shorts(played.toByteArray()), output, "the output file is exactly what was played");
        for (String stem : new String[] {"direct", "echo"}) {
            short[] samples = wav(recording.directory().resolve("radio-1-" + stem + ".wav"), 44100);
            assertEquals(output.length, samples.length, "the stems stay aligned with the output");
            for (short sample : samples) assertEquals(0, sample, "a panned radio has no rendered parts");
        }
        String events = Files.readString(recording.directory().resolve("events.csv"));
        assertTrue(events.contains(",1,closed,"), events);
        String readme = Files.readString(recording.directory().resolve("README.txt"));
        assertTrue(readme.contains("radio 1: radio, 44100 Hz, 4 blocks"), readme);

        long size = Files.size(recording.directory().resolve("radio-1-output.wav"));
        recording.track(session, "radio", 44100).played(AcousticRecording.Played.silent("late", Vec3.ZERO, AHEAD, Vec3.ZERO));
        Thread.sleep(50);
        assertEquals(size, Files.size(recording.directory().resolve("radio-1-output.wav")), "nothing is written after the stop");
        AcousticRecording.start(root, 1).stop().get(10, TimeUnit.SECONDS);
    }

    /** Rendered blocks keep their parts before the limiter: direct plus echo is what was played. */
    @Test void renderedBlocksKeepTheirPartsAndWhatTheyWereRenderedWith() throws Exception {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        var session = new RadioSimulationSession(new AudioFormat(44100, 16, 1, true, false), 0);
        AcousticRecording recording = AcousticRecording.start(root, 10);
        int blocks = 40;
        try {
            Vec3 source = new Vec3(4, 0, 1);
            session.setView(source, Vec3.ZERO, AHEAD, UP, true);
            awaitMembership(session);
            SharedReflectionSimulator.simulate(List.of(session), new RadioSimulationSessionTest.Room(), 1, System.nanoTime());
            drainReflectionWorker();
            for (int block = 0; block < blocks; block++) session.process(constant(block < 8 ? 3000 : 0), false);
        } finally {
            session.close();
            drainReflectionWorker();
        }
        recording.stop().get(10, TimeUnit.SECONDS);
        Path directory = recording.directory();

        List<Map<String, String>> rows = csv(directory.resolve("timeline.csv"));
        assertEquals(blocks, rows.size());
        for (Map<String, String> row : rows) {
            assertEquals("rendered", row.get("mode"));
            assertEquals("convolved", row.get("reflections"));
            assertEquals(Integer.toString(SharedReflectionSimulator.ECHO_SLOTS), row.get("ir_count"), "the first run fills every echo slot");
            assertFalse(row.get("delay_ms").isEmpty());
            assertEquals(3, Double.parseDouble(row.get("wet_scale")), 0.2);
        }
        assertTrue(Double.parseDouble(rows.get(2).get("direct_db")) > -40, "the direct sound is measured");
        assertTrue(Double.parseDouble(rows.get(20).get("echo_db")) > -90, "the reverb rings after the input stops");
        assertTrue(Double.parseDouble(rows.get(20).get("direct_db")) < -100, "the direct sound has stopped by then");

        short[] output = wav(directory.resolve("radio-1-output.wav"), 44100);
        short[] direct = wav(directory.resolve("radio-1-direct.wav"), 44100);
        short[] echo = wav(directory.resolve("radio-1-echo.wav"), 44100);
        assertEquals(output.length, direct.length);
        assertEquals(output.length, echo.length);
        double energy = 0;
        int compared = 0;
        for (int block = 0; block < blocks; block++) {
            // Where the limiter leaves the block alone, the played block is the sum of its parts.
            Map<String, String> row = rows.get(block);
            if (Double.parseDouble(row.get("peak")) > 0.9 || Double.parseDouble(row.get("limiter_gain")) < 0.9999) continue;
            compared++;
            for (int i = block * SteamRenderer.FRAME * 2; i < (block + 1) * SteamRenderer.FRAME * 2; i++) {
                energy += (double) echo[i] * echo[i];
                assertEquals(output[i], 2 * (direct[i] + echo[i]), 4, "the stems are the played parts at half scale, sample " + i);
            }
        }
        assertTrue(compared > blocks / 2, "most blocks are below the limiter: " + compared);
        assertTrue(energy > 0, "the echo stem carries the reflections");
        String events = Files.readString(directory.resolve("events.csv"));
        assertTrue(events.contains(",,reflection_run,\"ms="), events);
        assertTrue(events.contains("radios=1:0+1:1+1:2 of 1"), "radio:slot per source: " + events);
        assertTrue(events.contains(",1,ir_ready,\"ir_count=1 slot=0 "), events);
        assertTrue(events.contains(",1,ir_ready,\"ir_count=3 slot=2 "), events);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static short[] shorts(byte[] bytes) {
        short[] samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

    /** The samples of a 16-bit stereo WAV file, checking its header against its length. */
    private static short[] wav(Path file, int rate) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(bytes.length - 8, header.getInt(4));
        assertEquals("WAVEfmt ", new String(bytes, 8, 8, StandardCharsets.US_ASCII));
        assertEquals(1, header.getShort(20), "PCM");
        assertEquals(2, header.getShort(22), "stereo");
        assertEquals(rate, header.getInt(24));
        assertEquals(16, header.getShort(34));
        assertEquals("data", new String(bytes, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(bytes.length - 44, header.getInt(40));
        return shorts(Arrays.copyOfRange(bytes, 44, bytes.length));
    }

    private static List<Map<String, String>> csv(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        String[] names = lines.get(0).split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            assertEquals(names.length, cells.length, "every row has every column: " + line);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < names.length; i++) row.put(names[i], cells[i]);
            rows.add(row);
        }
        return rows;
    }
}
