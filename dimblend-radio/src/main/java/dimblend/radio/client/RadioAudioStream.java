package dimblend.radio.client;

import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.resources.ResourceLocation;

/** Minecraft adapter for the independently testable PCM feed/cursor. */
public final class RadioAudioStream implements FiniteAudioStream {
    private final RadioPcmFeed.FeedStream stream;

    private RadioAudioStream(RadioPcmFeed.FeedStream stream) {
        this.stream = stream;
    }

    public static RadioAudioStream open(ResourceLocation path) {
        String p = path.getPath();
        if (!path.getNamespace().equals("dimblend_radio")
                || !p.startsWith("sounds/radio/") || !p.endsWith(".ogg")) {
            return null;
        }
        var stream = RadioPcmFeed.open(p.substring("sounds/radio/".length(), p.length() - ".ogg".length()));
        return stream == null ? null : new RadioAudioStream(stream);
    }

    @Override
    public AudioFormat getFormat() {
        return this.stream.getFormat();
    }

    @Override
    public ByteBuffer read(int bytes) {
        return this.stream.read(bytes);
    }

    @Override
    public ByteBuffer readAll() {
        return this.stream.readAll();
    }

    @Override
    public void close() {
        this.stream.close();
    }
}
