package dimblend.experience.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which reward wagons exist, which have been built, and which have already dissolved.
 *
 * <p><b>Persisted because the alternative is a duplicate.</b> Without a record, every
 * server start would walk past the same region end and build a second wagon on top of the
 * first. The record is keyed by the wagon's centre X, so a save written before this feature
 * simply starts with no entries and grows them on the next visit.
 *
 * <p><b>A dissolved wagon stays dissolved.</b> The player removed its contents on purpose;
 * rebuilding it would hand them a second helping every time they come back. So the record
 * keeps the centre and a flag rather than deleting the entry.
 *
 * <p><b>Nothing about the blocks is stored.</b> Whether a wagon is still there is read from
 * the world, so a save that was edited or restored cannot be told a wagon exists that does
 * not.
 */
public final class RewardWagonSites extends SavedData {
    private static final String FILE_ID = "dimblend_reward_wagons";
    private static final Factory<RewardWagonSites> FACTORY =
            new Factory<>(RewardWagonSites::new, (tag, registries) -> load(tag), null);

    /** One wagon this world has seen. */
    public static final class Site {
        /** Centre block X on the corridor line. */
        public final int centerX;
        public boolean built;
        /** The sub-level this site assembled into, so a wagon is recognised by identity rather than by block ids. */
        public java.util.UUID subLevelId;
        public boolean dissolved;
        /** Failed build attempts, so a broken site is given up instead of retried forever. */
        public int attempts;
        /** Game time at which the sub-level must be removed; 0 when no dissolve is pending. Persisted as game time rather than a countdown so the half second is real time, not visit count. */
        public long dissolveDeadline;
        /** Whether the dissolve's particles and sound have already played. */
        public boolean effectsPlayed;

        Site(int centerX) {
            this.centerX = centerX;
        }
    }

    private final Map<Integer, Site> sites = new LinkedHashMap<>();

    private RewardWagonSites() {
    }

    public static RewardWagonSites get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    /** The record for one centre X, creating it when absent. */
    public Site site(int centerX) {
        Site existing = sites.get(centerX);
        if (existing != null) {
            return existing;
        }
        Site created = new Site(centerX);
        sites.put(centerX, created);
        setDirty();
        return created;
    }

    public Site find(int centerX) {
        return sites.get(centerX);
    }

    /** A snapshot, so a tick loop can mutate while iterating. */
    public List<Site> snapshot() {
        return new ArrayList<>(sites.values());
    }

    /**
     * The site whose sub-level this is, or {@code null}.
     *
     * <p>This is how everything else recognises a wagon: by the identity of the sub-level it
     * assembled into, not by which block ids it happens to contain. The block-id test would
     * miss a motor wagon's motor, clutch and lever, leaving that whole side of the deck
     * unprotected against placement.
     */
    public Site findBySubLevel(java.util.UUID subLevelId) {
        if (subLevelId == null) {
            return null;
        }
        for (Site site : sites.values()) {
            if (subLevelId.equals(site.subLevelId)) {
                return site;
            }
        }
        return null;
    }

    /** Forgets every record. Used by the tests and by a config reset, never at runtime. */
    public void clear() {
        sites.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Site site : sites.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("center_x", site.centerX);
            entry.putBoolean("built", site.built);
            if (site.subLevelId != null) {
                entry.putUUID("sub_level_id", site.subLevelId);
            }
            entry.putBoolean("dissolved", site.dissolved);
            entry.putInt("attempts", site.attempts);
            entry.putLong("dissolve_deadline", site.dissolveDeadline);
            entry.putBoolean("effects_played", site.effectsPlayed);
            list.add(entry);
        }
        tag.put("wagons", list);
        return tag;
    }

    private static RewardWagonSites load(CompoundTag tag) {
        RewardWagonSites data = new RewardWagonSites();
        ListTag list = tag.getList("wagons", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            Site site = new Site(entry.getInt("center_x"));
            site.built = entry.getBoolean("built");
            if (entry.hasUUID("sub_level_id")) {
                site.subLevelId = entry.getUUID("sub_level_id");
            }
            site.dissolved = entry.getBoolean("dissolved");
            site.attempts = entry.getInt("attempts");
            site.dissolveDeadline = entry.getLong("dissolve_deadline");
            site.effectsPlayed = entry.getBoolean("effects_played");
            data.sites.put(site.centerX, site);
        }
        return data;
    }

    /** Whether any site still needs work, so an idle server can skip the whole pass. */
    public boolean hasLiveSites() {
        for (Site site : sites.values()) {
            if (!site.dissolved) {
                return true;
            }
        }
        return false;
    }

    /** The block position of a wagon's centre for a given centre X. */
    public static BlockPos centerOf(int centerX) {
        return RewardWagonBuilder.center(centerX);
    }
}
