package dimblend.radio.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioPcmFeedTest {
    private static final AudioFormat FORMAT = new AudioFormat(1000, 16, 1, true, false);

    private static byte[] tone(int seconds) {
        ByteBuffer pcm = ByteBuffer.allocate(seconds * 2000).order(ByteOrder.LITTLE_ENDIAN);
        while (pcm.hasRemaining()) {
            pcm.putShort((short) 1000);
        }
        return pcm.array();
    }

    @Test
    void sameSongAtDifferentOffsetsCannotReplaceAnotherRadiosFeed() throws Exception {
        byte[] pcm = tone(10);
        var first = RadioPcmFeed.register(FORMAT, pcm, 0);
        var late = RadioPcmFeed.register(FORMAT, pcm, 8);
        assertNotEquals(first.id(), late.id());
        // Delay opening the first until after the second has been registered (old hash-map race).
        try (RadioPcmFeed.FeedStream a = RadioPcmFeed.open(first.id()); RadioPcmFeed.FeedStream b = RadioPcmFeed.open(late.id())) {
            assertEquals(20000, a.readAll().remaining());
            assertEquals(4000, b.readAll().remaining());
            assertTrue(first.exhausted());
            assertTrue(late.exhausted());
        }
        assertNull(RadioPcmFeed.open(first.id()));
        assertNull(RadioPcmFeed.open(late.id()));
    }

    @Test
    void interruptionBeforeEofIsDistinctFromExhaustedStream() throws Exception {
        var feed = RadioPcmFeed.register(FORMAT, tone(10), 0);
        try (RadioPcmFeed.FeedStream stream = RadioPcmFeed.open(feed.id())) {
            assertEquals(2000, stream.read(2000).remaining());
            assertFalse(feed.exhausted());
        }
        assertFalse(feed.exhausted());
        assertNull(RadioPcmFeed.open(feed.id()));
    }

    @Test
    void finalReadMarksEofAndSubsequentReadsNeverRepeatData() throws Exception {
        var feed = RadioPcmFeed.register(FORMAT, tone(2), 1);
        try (RadioPcmFeed.FeedStream stream = RadioPcmFeed.open(feed.id())) {
            assertEquals(1000, stream.read(1000).remaining());
            assertFalse(feed.exhausted());
            assertEquals(1000, stream.read(4000).remaining());
            assertTrue(feed.exhausted());
            assertEquals(0, stream.read(4000).remaining());
        }
    }

    @Test
    void resumedStreamFadesWithoutChangingSharedPcmOrAnotherStream() throws Exception {
        byte[] pcm = tone(2);
        byte[] original = pcm.clone();
        var feed = RadioPcmFeed.register(FORMAT, pcm, 1);
        try (RadioPcmFeed.FeedStream stream = RadioPcmFeed.open(feed.id())) {
            ByteBuffer beginning = stream.read(10).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0, beginning.getShort(0));
            assertEquals(400, beginning.getShort(8));
            ByteBuffer next = stream.read(20).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(500, next.getShort(0));
            assertEquals(1000, next.getShort(10));
            assertArrayEquals(original, pcm);
        }
    }

    @Test
    void releasingAnUnopenedFeedDoesNotLeakOrAffectOtherRequests() {
        var first = RadioPcmFeed.register(FORMAT, tone(1), 0);
        var other = RadioPcmFeed.register(FORMAT, tone(1), 0);
        first.release();
        assertNull(RadioPcmFeed.open(first.id()));
        assertNotNull(RadioPcmFeed.open(other.id()));
        other.release();
    }
}
