package dimblend.radio.acoustics;

import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.CRC32C;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Client-thread cache; values own copies, never the live palette or its level/chunk. */
public final class AcousticPaletteCache<T> {
    public record Frozen<T>(PalettedContainer<T> blocks, long fingerprint, long version) { }
    private final Map<PalettedContainer<T>, Frozen<T>> copies = new WeakHashMap<>();

    public Frozen<T> freeze(PalettedContainer<T> live) {
        if (!(live instanceof AcousticPaletteVersion versioned)) {
            // Dedicated-server GameTests or integrations without the client mixin stay correct.
            return copy(live, 0);
        }
        return freeze(live, versioned.dimblend$acousticVersion());
    }

    Frozen<T> freeze(PalettedContainer<T> live, long version) {
        Frozen<T> cached = copies.get(live);
        if (cached == null || cached.version != version) {
            cached = copy(live, version);
            copies.put(live, cached);
        }
        return cached;
    }

    private Frozen<T> copy(PalettedContainer<T> live, long version) {
        PalettedContainer<T> frozen = live.copy();
        return new Frozen<>(frozen, fingerprint(frozen), version);
    }

    static long fingerprint(PalettedContainer<?> blocks) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            blocks.write(buffer);
            CRC32C hash = new CRC32C();
            hash.update(buffer.nioBuffer());
            return hash.getValue();
        } finally { buffer.release(); }
    }

    public void clear() { copies.clear(); }
}
