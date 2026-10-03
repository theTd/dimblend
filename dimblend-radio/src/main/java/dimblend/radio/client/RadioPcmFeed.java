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
        private volatile boolean ending;
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

        /**
         * Stops the input early, with a short fade instead of a click, and lets the processor play
         * out what is still sounding (delayed direct sound, reverb); the stream then ends by itself.
         *
         * @return false without a processor: nothing to play out, stop the channel instead
         */
        public boolean endInput() {
            if (this.processor == null) {
                return false;
            }
            this.ending = true;
            return true;
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
        /** Fade at a resumed start and at an early end. */
        private static final float FADE_SECONDS = 0.010f;
        private final Handle handle;
        private int cursor;
        /** Where the input ends: the data's end, or the end of the fade once {@link Handle#endInput} was called. */
        private int end;
        private int fadeOutStart = -1;

        FeedStream(Handle handle) {
            this.handle = handle;
            this.cursor = handle.start;
            this.end = handle.data.length;
        }

        public AudioFormat getFormat() {
            return handle.processor == null ? handle.format : handle.processor.format();
        }

        public boolean simulated() { return handle.processor != null; }

        public ByteBuffer read(int bytes) {
            int inputBytes = handle.processor == null ? bytes : bytes / 2;
            int frameSize = this.handle.format.getFrameSize();
            int fadeFrames = Math.round(this.handle.format.getSampleRate() * FADE_SECONDS);
            if (this.handle.ending && this.fadeOutStart < 0) {
                this.fadeOutStart = this.cursor;
                this.end = Math.min(this.end, this.cursor + fadeFrames * frameSize);
            }
            int n = Math.max(0, Math.min(inputBytes, this.end - this.cursor));
            ByteBuffer buf = ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(this.handle.data, this.cursor, n);
            buf.flip();
            // Fade the first 10ms of resumed audio without modifying the shared cache.
            int fadeInFrames = Math.min((this.handle.data.length - this.handle.start) / frameSize, fadeFrames);
            if (this.handle.start > 0 && fadeInFrames > 0) {
                int firstFrame = (this.cursor - this.handle.start) / frameSize;
                for (int f = 0; f < n / frameSize && firstFrame + f < fadeInFrames; f++) {
                    for (int c = 0; c < this.handle.format.getChannels(); c++) {
                        int i = f * frameSize + c * 2;
                        buf.putShort(i, (short) (buf.getShort(i) * (firstFrame + f) / fadeInFrames));
                    }
                }
            }
            if (this.fadeOutStart >= 0 && fadeFrames > 0) {
                int firstFrame = (this.cursor - this.fadeOutStart) / frameSize;
                for (int f = 0; f < n / frameSize; f++) {
                    for (int c = 0; c < this.handle.format.getChannels(); c++) {
                        int i = f * frameSize + c * 2;
                        buf.putShort(i, (short) (buf.getShort(i) * (fadeFrames - 1 - firstFrame - f) / fadeFrames));
                    }
                }
            }
            this.cursor += n;
            boolean ended = this.cursor >= this.end;
            RadioPcmProcessor processor = handle.processor;
            ByteBuffer result = processor == null ? buf : processor.process(buf, ended);
            this.handle.exhausted = ended && (processor == null || !processor.hasTail());
            return result;
        }

        public ByteBuffer readAll() {
            return read((this.end - this.cursor) * (handle.processor == null ? 1 : 2));
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
