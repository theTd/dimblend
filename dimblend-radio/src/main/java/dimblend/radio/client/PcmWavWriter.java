package dimblend.radio.client;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A 16-bit PCM WAV file written as it grows. {@link #flush} keeps the header's sizes current, so
 * the file stays readable up to the last flush if the game stops before {@link #close}.
 */
final class PcmWavWriter implements Closeable {
    private static final int HEADER = 44;
    private final FileChannel file;
    private final int channels;
    private ByteBuffer scratch = ByteBuffer.allocate(0);
    private long dataBytes;
    /** Samples that were out of range and clamped. */
    private long clipped;

    PcmWavWriter(Path path, int rate, int channels) throws IOException {
        this.channels = channels;
        file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        ByteBuffer header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(HEADER - 8)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(0);
        file.write(header.flip(), 0);
        file.position(HEADER);
    }

    /**
     * Appends {@code frames} frames, each sample times {@code scale}.
     *
     * @param samples one array per channel, or null for silence
     */
    void write(float[][] samples, int frames, float scale) throws IOException {
        int bytes = frames * channels * 2;
        if (scratch.capacity() < bytes) scratch = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        scratch.clear();
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                float value = samples == null ? 0 : samples[c][i] * scale;
                if (value > 1 || value < -1) clipped++;
                scratch.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 32767))));
            }
        }
        scratch.flip();
        while (scratch.hasRemaining()) file.write(scratch);
        dataBytes += bytes;
    }

    /** Writes the current sizes into the header. */
    void flush() throws IOException {
        long size = Math.min(dataBytes, 0xFFFF_FFFFL - HEADER);
        ByteBuffer field = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        file.write(field.putInt(0, (int) (HEADER - 8 + size)), 4);
        file.write(field.clear().putInt(0, (int) size), 40);
    }

    long dataBytes() { return dataBytes; }

    long clipped() { return clipped; }

    @Override public void close() throws IOException {
        try { flush(); }
        finally { file.close(); }
    }
}
