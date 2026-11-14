package dimblend.dragonchase.chase;

import dimblend.dragonchase.structure.CarKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The chase's whole memory: phase, the one prologue, the nest, the dragon, the live breath zones and
 * the regions already used. Persisted per level so a restart resumes the chase (requirements.md
 * §2.7, §3.3-§3.6).
 *
 * <p>Config is not stored here: tuning values are read through {@code api.DragonChaseTuning} each
 * time they are needed, so a changed panel value takes effect without a world reload.
 *
 * <p>Everything is server-thread state. Four fields are deliberately transient:
 * {@code graceUntil} re-arms on every load, {@code markerSet} and {@code nestTicketEnsured} mirror
 * state the world owns, and the retry clocks only pace build attempts.
 */
public final class DragonChaseSavedData extends SavedData {
    /** §3.2-§3.6: the chase state machine. There is one chase per level and only one per server. */
    public enum Phase {
        /** No prologue running, no nest: the director watches for both triggers. */
        IDLE,
        /** A nest is queued or standing; it waits for the train to come within 128 blocks (§3.3). */
        WAITING,
        /** The chase dragon is alive and the nest flees (§3.4). */
        ACTIVE,
        /** The dragon is dead, rewards are placed, the speed floor and marker stay until the nest is gone (§3.6). */
        WON
    }

    private static final String FILE_ID = "dimblend_dragon_chase";
    private static final Factory<DragonChaseSavedData> FACTORY =
            new Factory<>(DragonChaseSavedData::new, (tag, registries) -> load(tag), null);

    public Phase phase = Phase.IDLE;

    /** §3.6: bumped on every kill, per world. */
    public int generation = 1;

    /** §3.2 decision prologue-repeat: the prologue spawns once per world. */
    public boolean prologueUsed;
    public UUID prologueDragonId;
    public boolean prologueDeparting;
    /** Set by {@link ChaseListener#onDamageTaken}: any damage ends the prologue (§3.2). */
    public boolean prologueDamageTaken;
    public long prologueSpawnTime;
    public int prologueSlowTicks;
    /** Last orbit centre, kept for the 512-block advancement radius when the dragon departs. */
    public double orbitX;
    public double orbitZ;

    /** §3.3: regions that already carried a nest; a region is never reused. */
    public final Set<Integer> usedNestRegions = new LinkedHashSet<>();
    /** Queued nest X, null when none is queued. The nest is built once its footprint chunks are loaded. */
    public Integer pendingNestX;
    public int nestRegion;
    public UUID nestId;
    /** The nest's placement X (region local 512); the live centre is read from the sub-level. */
    public int nestCenterX;

    public UUID dragonId;
    /** §2.8: the health pool fixed at spawn, kept so a reload still reports what this chase rolled. */
    public float maxHealth;
    /** §3.4: blocks the nest has travelled since the chase started. */
    public double nestDistance;
    /** Nest centre X of the previous tick, NaN before the chase starts. */
    public double lastNestCenterX = Double.NaN;
    public long startedAt;

    /** §3.4: regions already forced to barren End, so each is forced once. */
    public final Set<Integer> forcedEndRegions = new LinkedHashSet<>();
    public double fireballAccumulator;
    public long fireballCooldownUntil;
    /** §3.4: one flag per {@link ChaseMath#HEALTH_THRESHOLDS} entry, so each breath fires once. */
    public final boolean[] breathFired = new boolean[ChaseMath.HEALTH_THRESHOLDS.length];

    /** §3.4: one lingering breath cloud on the track, until a car (never the nest) crosses it. */
    public record BreathZone(double minX, double maxX) {
        public BreathZone {
            if (minX > maxX) {
                double tmp = minX;
                minX = maxX;
                maxX = tmp;
            }
        }
    }

    /** §3.4: every breath cloud still on the track, in the order they were laid. */
    public final List<BreathZone> breathZones = new ArrayList<>();

    /** §3.4: a car dropped by a fireball, waiting for its footprint to load. */
    public CarKind pendingCarKind;
    public int pendingCarX;
    public double pendingCarVx;
    public long pendingCarDeadline;

    /** Ticks until lose checks and the prologue slow rule resume (§2.7). Transient: re-arms on load. */
    public long graceUntil = Long.MIN_VALUE;
    /** Retry clocks for the two deferred builds; transient because they only pace attempts. */
    public long pendingNestRetryAt;
    public long pendingCarRetryAt;
    /** Whether this director currently owns {@code BandTrainMarker}'s override. Transient. */
    public boolean markerSet;
    /**
     * Whether this chase already re-issued the nest's {@code COMMAND_FORCED} ticket after a load.
     * Transient: a restart drops the ticket with the chunk, so it is restored on the first tick
     * that sees the nest again.
     */
    public boolean nestTicketEnsured;

    public static DragonChaseSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    /** §2.7: the first tick after a load starts the 10 s grace. */
    public void beginGrace(long gameTime) {
        if (this.graceUntil == Long.MIN_VALUE) {
            this.graceUntil = gameTime + ChaseMath.LOAD_GRACE_TICKS;
        }
    }

    public boolean graceElapsed(long gameTime) {
        return gameTime >= this.graceUntil;
    }

    /**
     * Forgets the running chase but keeps what the world remembers: generation, the prologue, the
     * regions already used and the region overrides already written.
     */
    public void forgetChase() {
        this.phase = Phase.IDLE;
        this.pendingNestX = null;
        this.nestRegion = 0;
        this.nestId = null;
        this.nestCenterX = 0;
        this.dragonId = null;
        this.maxHealth = 0.0F;
        this.nestDistance = 0.0D;
        this.lastNestCenterX = Double.NaN;
        this.startedAt = 0L;
        this.fireballAccumulator = 0.0D;
        this.fireballCooldownUntil = 0L;
        java.util.Arrays.fill(this.breathFired, false);
        this.breathZones.clear();
        this.pendingCarKind = null;
        this.pendingCarX = 0;
        this.pendingCarVx = 0.0D;
        this.pendingCarDeadline = 0L;
        this.pendingNestRetryAt = 0L;
        this.pendingCarRetryAt = 0L;
        this.nestTicketEnsured = false;
        setDirty();
    }

    /** Debug {@code /dragonchase reset}: also forgets generation, the prologue and which regions were used. */
    public void forgetEverything() {
        forgetChase();
        this.generation = 1;
        this.prologueUsed = false;
        this.prologueDragonId = null;
        this.prologueDeparting = false;
        this.prologueDamageTaken = false;
        this.prologueSpawnTime = 0L;
        this.prologueSlowTicks = 0;
        this.orbitX = 0.0D;
        this.orbitZ = 0.0D;
        this.usedNestRegions.clear();
        this.forcedEndRegions.clear();
        this.graceUntil = Long.MIN_VALUE;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("phase", this.phase.name());
        tag.putInt("generation", this.generation);

        tag.putBoolean("prologue_used", this.prologueUsed);
        tag.putBoolean("prologue_departing", this.prologueDeparting);
        tag.putBoolean("prologue_damaged", this.prologueDamageTaken);
        tag.putLong("prologue_spawn_time", this.prologueSpawnTime);
        tag.putInt("prologue_slow_ticks", this.prologueSlowTicks);
        tag.putDouble("orbit_x", this.orbitX);
        tag.putDouble("orbit_z", this.orbitZ);
        if (this.prologueDragonId != null) {
            tag.putUUID("prologue_dragon", this.prologueDragonId);
        }

        tag.putIntArray("used_nest_regions", intArray(this.usedNestRegions));
        if (this.pendingNestX != null) {
            tag.putInt("pending_nest_x", this.pendingNestX);
        }
        tag.putInt("nest_region", this.nestRegion);
        tag.putInt("nest_center_x", this.nestCenterX);
        if (this.nestId != null) {
            tag.putUUID("nest_id", this.nestId);
        }

        if (this.dragonId != null) {
            tag.putUUID("dragon_id", this.dragonId);
        }
        tag.putFloat("max_health", this.maxHealth);
        tag.putDouble("nest_distance", this.nestDistance);
        if (!Double.isNaN(this.lastNestCenterX)) {
            tag.putDouble("last_nest_center_x", this.lastNestCenterX);
        }
        tag.putLong("started_at", this.startedAt);

        tag.putIntArray("forced_end_regions", intArray(this.forcedEndRegions));
        tag.putDouble("fireball_accumulator", this.fireballAccumulator);
        tag.putLong("fireball_cooldown_until", this.fireballCooldownUntil);
        CompoundTag thresholds = new CompoundTag();
        for (int i = 0; i < this.breathFired.length; i++) {
            thresholds.putBoolean(Integer.toString(i), this.breathFired[i]);
        }
        tag.put("breath_fired", thresholds);

        ListTag zones = new ListTag();
        for (BreathZone zone : this.breathZones) {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("min_x", zone.minX());
            entry.putDouble("max_x", zone.maxX());
            zones.add(entry);
        }
        tag.put("breath_zones", zones);

        if (this.pendingCarKind != null) {
            CompoundTag car = new CompoundTag();
            car.putString("kind", this.pendingCarKind.name());
            car.putInt("x", this.pendingCarX);
            car.putDouble("vx", this.pendingCarVx);
            car.putLong("deadline", this.pendingCarDeadline);
            tag.put("pending_car", car);
        }
        return tag;
    }

    /** Package-private so the unit test can round-trip a tag without a running server. */
    static DragonChaseSavedData load(CompoundTag tag) {
        DragonChaseSavedData data = new DragonChaseSavedData();
        data.phase = phaseOf(tag.getString("phase"));
        data.generation = Math.max(1, tag.getInt("generation"));

        data.prologueUsed = tag.getBoolean("prologue_used");
        data.prologueDeparting = tag.getBoolean("prologue_departing");
        data.prologueDamageTaken = tag.getBoolean("prologue_damaged");
        data.prologueSpawnTime = tag.getLong("prologue_spawn_time");
        data.prologueSlowTicks = tag.getInt("prologue_slow_ticks");
        data.orbitX = tag.getDouble("orbit_x");
        data.orbitZ = tag.getDouble("orbit_z");
        if (tag.hasUUID("prologue_dragon")) {
            data.prologueDragonId = tag.getUUID("prologue_dragon");
        }

        for (int region : tag.getIntArray("used_nest_regions")) {
            data.usedNestRegions.add(region);
        }
        if (tag.contains("pending_nest_x")) {
            data.pendingNestX = tag.getInt("pending_nest_x");
        }
        data.nestRegion = tag.getInt("nest_region");
        data.nestCenterX = tag.getInt("nest_center_x");
        if (tag.hasUUID("nest_id")) {
            data.nestId = tag.getUUID("nest_id");
        }

        if (tag.hasUUID("dragon_id")) {
            data.dragonId = tag.getUUID("dragon_id");
        }
        data.maxHealth = tag.getFloat("max_health");
        data.nestDistance = tag.getDouble("nest_distance");
        data.lastNestCenterX = tag.contains("last_nest_center_x") ? tag.getDouble("last_nest_center_x") : Double.NaN;
        data.startedAt = tag.getLong("started_at");

        for (int region : tag.getIntArray("forced_end_regions")) {
            data.forcedEndRegions.add(region);
        }
        data.fireballAccumulator = tag.getDouble("fireball_accumulator");
        data.fireballCooldownUntil = tag.getLong("fireball_cooldown_until");
        CompoundTag thresholds = tag.getCompound("breath_fired");
        for (int i = 0; i < data.breathFired.length; i++) {
            data.breathFired[i] = thresholds.getBoolean(Integer.toString(i));
        }

        ListTag zones = tag.getList("breath_zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zones.size(); i++) {
            CompoundTag entry = zones.getCompound(i);
            data.breathZones.add(new BreathZone(entry.getDouble("min_x"), entry.getDouble("max_x")));
        }
        if (data.breathZones.isEmpty() && tag.getBoolean("breath_zone")) {
            // Pre-multi-zone saves carried one zone as a boolean plus its two edges.
            data.breathZones.add(new BreathZone(tag.getDouble("breath_min_x"), tag.getDouble("breath_max_x")));
        }

        CompoundTag car = tag.getCompound("pending_car");
        if (!car.isEmpty()) {
            data.pendingCarKind = kindOf(car.getString("kind"));
            data.pendingCarX = car.getInt("x");
            data.pendingCarVx = car.getDouble("vx");
            data.pendingCarDeadline = car.getLong("deadline");
        }
        return data;
    }

    private static Phase phaseOf(String name) {
        try {
            return Phase.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return Phase.IDLE;
        }
    }

    private static CarKind kindOf(String name) {
        try {
            return CarKind.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static int[] intArray(Set<Integer> values) {
        int[] out = new int[values.size()];
        int index = 0;
        for (int value : values) {
            out[index++] = value;
        }
        return out;
    }
}
