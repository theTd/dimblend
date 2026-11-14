package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.api.RegionOverrides;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link RegionOverrideStore}: the map semantics the worldgen threads read, the region guard, and
 * the NBT round trip. No server is built here — the store's instance methods are the real
 * implementation, the installed router only adds the server/data-storage lookup around them.
 */
class RegionOverrideStoreTest {
    @Test
    void emptyStoreKnowsNothing() {
        RegionOverrideStore store = new RegionOverrideStore();
        assertEquals(Optional.empty(), store.overrideOf(40));
        assertEquals(Optional.empty(), store.overrideOf(-40));
    }

    @Test
    void forcedLaneIsVisibleImmediatelyAndIdempotently() {
        RegionOverrideStore store = new RegionOverrideStore();
        assertTrue(store.force(40, RegionOverrides.Override.END_BARREN));
        assertEquals(Optional.of(RegionOverrides.Override.END_BARREN), store.overrideOf(40));
        assertTrue(store.isDirty(), "a new override must be persisted");

        assertTrue(store.force(40, RegionOverrides.Override.END_BARREN), "idempotent call succeeds");
        assertEquals(1, store.save(new CompoundTag(), null).getList("entries", Tag.TAG_COMPOUND).size(),
                "rewriting the same value must not duplicate the entry");
    }

    @Test
    void scriptedAndNegativeRegionsAreRejected() {
        RegionOverrideStore store = new RegionOverrideStore();
        assertFalse(store.force(33, RegionOverrides.Override.END_BARREN), "33 is scripted (voidscape)");
        assertFalse(store.force(0, RegionOverrides.Override.END_BARREN));
        assertFalse(store.force(-40, RegionOverrides.Override.END_BARREN), "only the x+ side is forced");
        assertFalse(store.force(32, RegionOverrides.Override.END_BARREN), "32 is the scripted End region");
        assertFalse(store.force(40, null), "null override is not a write");
        assertFalse(store.isDirty(), "rejected calls must not mark the data dirty");
        assertEquals(Optional.empty(), store.overrideOf(33));
        assertEquals(Optional.empty(), store.overrideOf(-40));
    }

    @Test
    void firstRandomRegionMatchesTheAssigner() {
        assertEquals(BandLaneAssigner.FAR_RANDOM_START, RegionOverrideStore.FIRST_RANDOM_REGION);
        assertFalse(RegionOverrideStore.isRandomRegion(RegionOverrideStore.FIRST_RANDOM_REGION - 1));
        assertTrue(RegionOverrideStore.isRandomRegion(RegionOverrideStore.FIRST_RANDOM_REGION));
        assertFalse(RegionOverrideStore.isRandomRegion(-RegionOverrideStore.FIRST_RANDOM_REGION));
    }

    @Test
    void tablesAreImmutableSoReadersNeverSeeAPartialWrite() {
        RegionOverrideStore.Table empty = RegionOverrideStore.Table.EMPTY;
        RegionOverrideStore.Table written = empty.with(40, RegionOverrides.Override.END_BARREN);
        assertEquals(Optional.empty(), empty.get(40), "the replaced table keeps its old contents");
        assertEquals(Optional.of(RegionOverrides.Override.END_BARREN), written.get(40));
    }

    @Test
    void savedDataRoundTripsEveryEntry() {
        RegionOverrideStore store = new RegionOverrideStore();
        store.force(99, RegionOverrides.Override.END_BARREN);
        store.force(40, RegionOverrides.Override.END_BARREN);

        CompoundTag tag = store.save(new CompoundTag(), null);
        RegionOverrideStore reloaded = RegionOverrideStore.read(tag, null);

        assertEquals(2, reloaded.save(new CompoundTag(), null).getList("entries", Tag.TAG_COMPOUND).size());
        assertEquals(Optional.of(RegionOverrides.Override.END_BARREN), reloaded.overrideOf(40));
        assertEquals(Optional.of(RegionOverrides.Override.END_BARREN), reloaded.overrideOf(99));
        assertEquals(Optional.empty(), reloaded.overrideOf(41));
    }

    @Test
    void loadDropsEntriesAPreviousVersionShouldNeverHaveWritten() {
        ListTag entries = new ListTag();
        entries.add(entry(40, "END_BARREN"));
        entries.add(entry(10, "END_BARREN"));
        entries.add(entry(-40, "END_BARREN"));
        entries.add(entry(41, "SOMETHING_ELSE"));
        CompoundTag tag = new CompoundTag();
        tag.put("entries", entries);

        RegionOverrideStore reloaded = RegionOverrideStore.read(tag, null);

        assertEquals(1, reloaded.save(new CompoundTag(), null).getList("entries", Tag.TAG_COMPOUND).size(),
                "only the legal, known entry survives");
        assertEquals(Optional.of(RegionOverrides.Override.END_BARREN), reloaded.overrideOf(40));
        assertEquals(Optional.empty(), reloaded.overrideOf(41));
        assertEquals(Optional.empty(), reloaded.overrideOf(10));
        assertEquals(Optional.empty(), reloaded.overrideOf(-40));
    }

    private static CompoundTag entry(int region, String override) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("region", region);
        entry.putString("override", override);
        return entry;
    }
}
