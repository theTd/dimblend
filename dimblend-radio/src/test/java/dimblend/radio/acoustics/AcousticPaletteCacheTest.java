package dimblend.radio.acoustics;

import net.minecraft.core.IdMapper;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticPaletteCacheTest {
    private static PalettedContainer<String> palette() {
        IdMapper<String> ids = new IdMapper<>();
        ids.add("air"); ids.add("stone");
        return new PalettedContainer<>(ids, "air", PalettedContainer.Strategy.SECTION_STATES);
    }

    @Test void unchangedSectionsReuseCopiesButEditsPreservePreviousSnapshot() {
        var cache = new AcousticPaletteCache<String>();
        var live = palette();
        var first = cache.freeze(live, 0);
        assertSame(first, cache.freeze(live, 0));
        live.set(2, 3, 4, "stone");
        var changed = cache.freeze(live, 1);
        assertNotSame(first, changed);
        assertEquals("air", first.blocks().get(2, 3, 4));
        assertEquals("stone", changed.blocks().get(2, 3, 4));
        assertNotEquals(first.fingerprint(), changed.fingerprint());
        assertSame(changed, cache.freeze(live, 1));
    }

    @Test void replacementPalettesAndWorldResetCannotReuseOldData() {
        var cache = new AcousticPaletteCache<String>();
        var live = palette();
        var first = cache.freeze(live, 0);
        assertNotSame(first, cache.freeze(palette(), 0));
        cache.clear();
        assertNotSame(first, cache.freeze(live, 0));
    }

    @Test void missingVersionMixinAlwaysCopiesForCorrectness() {
        var cache = new AcousticPaletteCache<String>();
        var live = palette();
        var first = cache.freeze(live);
        live.set(0, 0, 0, "stone");
        assertEquals("stone", cache.freeze(live).blocks().get(0, 0, 0));
        assertEquals("air", first.blocks().get(0, 0, 0));
    }
}
