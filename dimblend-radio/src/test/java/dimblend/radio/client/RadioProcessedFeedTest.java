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
}
