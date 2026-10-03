package dimblend.radio.client;

import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioProcessedFeedTest {
    @Test
    void stereoProcessingConsumesMonoFramesAndKeepsTheTailBeforeEof() {
        var mono = new AudioFormat(44100, 16, 1, true, false);
        var feed = RadioPcmFeed.register(mono, new byte[8], 0);
        var processor = new RadioPcmProcessor() {
            boolean tail;
            boolean closed;
            @Override public AudioFormat format() { return new AudioFormat(44100, 16, 2, true, false); }
            @Override public ByteBuffer process(ByteBuffer input, boolean end) {
                if (input.hasRemaining()) {
                    tail = end;
                    return ByteBuffer.allocateDirect(input.remaining() * 2);
                }
                tail = false;
                return ByteBuffer.allocateDirect(4);
            }
            @Override public boolean hasTail() { return tail; }
            @Override public void close() { closed = true; }
        };
        feed.setProcessor(processor);
        var stream = RadioPcmFeed.open(feed.id());
        assertEquals(2, stream.getFormat().getChannels());
        assertEquals(16, stream.read(16).remaining());
        assertFalse(feed.exhausted());
        assertEquals(4, stream.read(16).remaining());
        assertTrue(feed.exhausted());
        stream.close();
        assertTrue(processor.closed);
    }

    /** Passes the mono input through and reports a tail until {@code tailReads} empty reads went by. */
    private static final class Recorder implements RadioPcmProcessor {
        final java.util.List<short[]> inputs = new java.util.ArrayList<>();
        boolean ended;
        int tailReads = 3;
        @Override public AudioFormat format() { return new AudioFormat(1000, 16, 2, true, false); }
        @Override public ByteBuffer process(ByteBuffer input, boolean end) {
            short[] samples = new short[input.remaining() / 2];
            input.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            inputs.add(samples);
            ended |= end;
            if (samples.length == 0 && tailReads > 0) tailReads--;
            return ByteBuffer.allocateDirect(samples.length == 0 && tailReads == 0 ? 0 : 4);
        }
        @Override public boolean hasTail() { return ended && tailReads > 0; }
        @Override public void close() { }
    }

    @Test
    void anEarlyEndFadesTheInputOutAndPlaysTheTail() {
        var mono = new AudioFormat(1000, 16, 1, true, false);
        java.nio.ByteBuffer pcm = java.nio.ByteBuffer.allocate(2000 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (pcm.hasRemaining()) pcm.putShort((short) 1000);
        var feed = RadioPcmFeed.register(mono, pcm.array(), 0);
        var processor = new Recorder();
        feed.setProcessor(processor);
        try (var stream = RadioPcmFeed.open(feed.id())) {
            stream.read(200); // 50 mono frames
            assertFalse(processor.ended);
            assertTrue(feed.endInput());
            stream.read(200);
            short[] fade = processor.inputs.get(1);
            assertEquals(10, fade.length, "10 ms at 1 kHz, then the input ends");
            assertEquals(900, fade[0]);
            assertEquals(0, fade[9]);
            for (int i = 1; i < fade.length; i++) assertTrue(fade[i] < fade[i - 1]);
            assertTrue(processor.ended);
            assertFalse(feed.exhausted(), "the tail still plays");
            while (!feed.exhausted()) {
                stream.read(200);
                assertEquals(0, processor.inputs.get(processor.inputs.size() - 1).length, "no input after the end");
            }
            assertEquals(0, processor.tailReads);
        }
    }

    @Test
    void withoutAProcessorThereIsNothingToPlayOut() {
        var feed = RadioPcmFeed.register(new AudioFormat(1000, 16, 1, true, false), new byte[200], 0);
        assertFalse(feed.endInput());
        feed.release();
    }
}
