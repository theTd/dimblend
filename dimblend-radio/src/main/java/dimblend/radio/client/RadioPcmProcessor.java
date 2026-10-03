package dimblend.radio.client;

import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;

public interface RadioPcmProcessor extends AutoCloseable {
    AudioFormat format();
    ByteBuffer process(ByteBuffer mono, boolean endOfInput);
    boolean hasTail();
    /** The audio device ran dry and playback restarts with {@code buffers} queued. */
    default void starved(int buffers) { }
    @Override void close();
}
