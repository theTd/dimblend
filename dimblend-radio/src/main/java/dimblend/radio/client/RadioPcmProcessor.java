package dimblend.radio.client;

import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;

public interface RadioPcmProcessor extends AutoCloseable {
    AudioFormat format();
    ByteBuffer process(ByteBuffer mono, boolean endOfInput);
    boolean hasTail();
    @Override void close();
}
