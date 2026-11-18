package dimblend.dragonchase.chase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.dragonchase.music.ChaseMusicPhase;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * The chase's persisted state without a world: NBT in, NBT out. Covers the multi-zone breath
 * bookkeeping (several clouds may live at once, each consumed by its own car) and the one-way
 * migration from the pre-multi-zone single-zone save.
 */
class DragonChaseSavedDataTest {
    private static CompoundTag save(DragonChaseSavedData data) {
        // The registries argument is unused by this SavedData; a tag round-trip needs no server.
        return data.save(new CompoundTag(), null);
    }

    @Test
    void breathZonesRoundTripInOrder() {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.breathZones.add(new DragonChaseSavedData.BreathZone(100.0D, 160.0D));
        data.breathZones.add(new DragonChaseSavedData.BreathZone(500.0D, 540.0D));

        DragonChaseSavedData loaded = DragonChaseSavedData.load(save(data));

        assertEquals(List.of(
                new DragonChaseSavedData.BreathZone(100.0D, 160.0D),
                new DragonChaseSavedData.BreathZone(500.0D, 540.0D)), loaded.breathZones);
    }

    @Test
    void breathZoneNormalizesReversedEdges() {
        DragonChaseSavedData.BreathZone zone = new DragonChaseSavedData.BreathZone(160.0D, 100.0D);
        assertEquals(100.0D, zone.minX(), 0.0D);
        assertEquals(160.0D, zone.maxX(), 0.0D);
        // A zero-length breath segment is legal and stays a point zone.
        assertEquals(120.0D, new DragonChaseSavedData.BreathZone(120.0D, 120.0D).minX(), 0.0D);
    }

    @Test
    void loadMigratesTheLegacySingleZone() {
        CompoundTag legacy = new CompoundTag();
        legacy.putBoolean("breath_zone", true);
        legacy.putDouble("breath_min_x", 900.0D);
        legacy.putDouble("breath_max_x", 880.0D);

        DragonChaseSavedData loaded = DragonChaseSavedData.load(legacy);

        assertEquals(List.of(new DragonChaseSavedData.BreathZone(880.0D, 900.0D)), loaded.breathZones);
    }

    @Test
    void loadWithoutAnyZoneLeavesTheListEmpty() {
        DragonChaseSavedData loaded = DragonChaseSavedData.load(new CompoundTag());
        assertTrue(loaded.breathZones.isEmpty());
        // The legacy boolean being false must not invent a zone at the origin.
        CompoundTag inactive = new CompoundTag();
        inactive.putBoolean("breath_zone", false);
        assertTrue(DragonChaseSavedData.load(inactive).breathZones.isEmpty());
    }

    @Test
    void forgetChaseClearsZonesAndTheNestTicketFlag() {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.phase = DragonChaseSavedData.Phase.ACTIVE;
        data.generation = 4;
        data.prologueUsed = true;
        data.usedNestRegions.add(34);
        data.breathZones.add(new DragonChaseSavedData.BreathZone(10.0D, 50.0D));
        data.nestTicketEnsured = true;

        data.forgetChase();

        assertTrue(data.breathZones.isEmpty());
        assertFalse(data.nestTicketEnsured);
        assertEquals(DragonChaseSavedData.Phase.IDLE, data.phase);
        assertEquals(4, data.generation);
        assertTrue(data.prologueUsed);
        assertTrue(data.usedNestRegions.contains(34));
    }

    @Test
    void forgetEverythingResetsGenerationPrologueAndRegions() {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.phase = DragonChaseSavedData.Phase.WON;
        data.generation = 5;
        data.prologueUsed = true;
        data.usedNestRegions.add(36);
        data.forcedEndRegions.add(37);

        data.forgetEverything();

        assertEquals(DragonChaseSavedData.Phase.IDLE, data.phase);
        assertEquals(1, data.generation);
        assertFalse(data.prologueUsed);
        assertTrue(data.usedNestRegions.isEmpty());
        assertTrue(data.forcedEndRegions.isEmpty());
    }

    @Test
    void fireballAndBreathStateRoundTrip() {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.fireballAccumulator = 27.5D;
        data.fireballCooldownUntil = 1234L;
        data.breathFired[1] = true;
        data.breathFired[3] = true;

        DragonChaseSavedData loaded = DragonChaseSavedData.load(save(data));

        assertEquals(27.5D, loaded.fireballAccumulator, 0.0D,
                "damage banked while the dragon was busy must survive a reload");
        assertEquals(1234L, loaded.fireballCooldownUntil);
        assertFalse(loaded.breathFired[0]);
        assertTrue(loaded.breathFired[1]);
        assertFalse(loaded.breathFired[2]);
        assertTrue(loaded.breathFired[3]);
    }

    @Test
    void savesTheZonesAsACompoundList() {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.breathZones.add(new DragonChaseSavedData.BreathZone(1.0D, 2.0D));
        CompoundTag tag = save(data);

        ListTag zones = tag.getList("breath_zones", Tag.TAG_COMPOUND);
        assertEquals(1, zones.size());
        assertEquals(1.0D, zones.getCompound(0).getDouble("min_x"), 0.0D);
        assertEquals(2.0D, zones.getCompound(0).getDouble("max_x"), 0.0D);
    }

    @Test
    void musicStateRoundTripsSoARestartResumesTheSameTrack() {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.musicPhase = ChaseMusicPhase.PHASE_B;
        data.phaseATrack = 2;
        data.phaseBTrack = 3;
        data.phaseBTriggered = true;

        DragonChaseSavedData loaded = DragonChaseSavedData.load(save(data));

        assertEquals(ChaseMusicPhase.PHASE_B, loaded.musicPhase);
        assertEquals(2, loaded.phaseATrack);
        assertEquals(3, loaded.phaseBTrack);
        assertTrue(loaded.phaseBTriggered,
                "the one-way Phase B latch must survive a reload, or crystal healing could re-trigger it");
    }

    @Test
    void preMusicSavesLoadAsIdleWithNoRolledTrack() {
        CompoundTag oldSave = new CompoundTag();
        oldSave.putString("phase", DragonChaseSavedData.Phase.ACTIVE.name());
        oldSave.putFloat("max_health", 400.0F);

        DragonChaseSavedData loaded = DragonChaseSavedData.load(oldSave);

        assertEquals(ChaseMusicPhase.IDLE, loaded.musicPhase);
        // -1, not 0: track 0 is a real track, so a missing key must not pretend one was rolled.
        assertEquals(-1, loaded.phaseATrack);
        assertEquals(-1, loaded.phaseBTrack);
        assertFalse(loaded.phaseBTriggered);
    }

    @Test
    void unknownMusicPhaseNameLoadsAsIdle() {
        CompoundTag tag = new CompoundTag();
        tag.putString("music_phase", "PHASE_C");

        assertEquals(ChaseMusicPhase.IDLE, DragonChaseSavedData.load(tag).musicPhase);
    }

    @Test
    void forgetChaseClearsTheMusicStateOfTheFinishedChase() {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.musicPhase = ChaseMusicPhase.PHASE_B;
        data.phaseATrack = 1;
        data.phaseBTrack = 0;
        data.phaseBTriggered = true;

        data.forgetChase();

        assertEquals(ChaseMusicPhase.IDLE, data.musicPhase);
        assertEquals(-1, data.phaseATrack);
        assertEquals(-1, data.phaseBTrack);
        assertFalse(data.phaseBTriggered, "the next chase rolls both tracks again");
    }
}
