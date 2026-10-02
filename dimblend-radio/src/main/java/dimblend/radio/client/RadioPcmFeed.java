package dimblend.radio.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;

/** Immutable cached PCM, with a unique feed and cursor for every playback attempt. */
public final class RadioPcmFeed {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<String, Handle> PCM = new ConcurrentHashMap<>();

    public static final class Handle {
        private final String id;
        private final AudioFormat format;
        private final byte[] data;
        private final int start;
        private volatile boolean exhausted;
        private volatile RadioPcmProcessor processor;

        private Handle(String id, AudioFormat format, byte[] data, double offsetSec) {
            this.id = id;
            this.format = format;
            this.data = data;
            long frame = Math.min(data.length / format.getFrameSize(),
                    (long) (Math.max(0.0, offsetSec) * format.getSampleRate()));
            this.start = (int) (frame * format.getFrameSize());
        }

        public String id() {
            return this.id;
        }

        public boolean exhausted() {
            return this.exhausted;
        }

        public void release() {
            PCM.remove(this.id, this);
        }

        public void setProcessor(RadioPcmProcessor processor) {
            this.processor = processor;
        }

        public AudioFormat format() { return format; }
    }

    public static Handle register(AudioFormat format, byte[] data, double offsetSec) {
        String id = Long.toUnsignedString(NEXT_ID.incrementAndGet());
        Handle handle = new Handle(id, format, data, offsetSec);
        PCM.put(id, handle);
        return handle;
    }

    public static FeedStream open(String id) {
        Handle handle = PCM.get(id);
        return handle == null ? null : new FeedStream(handle);
    }

    public static final class FeedStream implements AutoCloseable {
        private final Handle handle;
        private int cursor;

        FeedStream(Handle handle) {
            this.handle = handle;
            this.cursor = handle.start;
        }

        public AudioFormat getFormat() {
            return handle.processor == null ? handle.format : handle.processor.format();
        }

        public boolean simulated() { return handle.processor != null; }

        public ByteBuffer read(int bytes) {
            int inputBytes = handle.processor == null ? bytes : bytes / 2;
            int n = Math.min(inputBytes, this.handle.data.length - this.cursor);
            ByteBuffer buf = ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(this.handle.data, this.cursor, n);
            buf.flip();
            // Fade the first 10ms of resumed audio without modifying the shared cache.
            int frameSize = this.handle.format.getFrameSize();
            int fadeFrames = Math.min((this.handle.data.length - this.handle.start) / frameSize,
                    Math.round(this.handle.format.getSampleRate() * 0.010f));
            if (this.handle.start > 0 && fadeFrames > 0) {
                int firstFrame = (this.cursor - this.handle.start) / frameSize;
                for (int f = 0; f < n / frameSize && firstFrame + f < fadeFrames; f++) {
                    for (int c = 0; c < this.handle.format.getChannels(); c++) {
                        int i = f * frameSize + c * 2;
                        buf.putShort(i, (short) (buf.getShort(i) * (firstFrame + f) / fadeFrames));
                    }
                }
            }
            this.cursor += n;
            boolean end = this.cursor >= this.handle.data.length;
            RadioPcmProcessor processor = handle.processor;
            ByteBuffer result = processor == null ? buf : processor.process(buf, end);
            this.handle.exhausted = end && (processor == null || !processor.hasTail());
            return result;
        }

        public ByteBuffer readAll() {
            return read((this.handle.data.length - this.cursor) * (handle.processor == null ? 1 : 2));
        }

        @Override
        public void close() {
            if (handle.processor != null) handle.processor.close();
            this.handle.release();
        }
    }

    private RadioPcmFeed() {
    }
}
