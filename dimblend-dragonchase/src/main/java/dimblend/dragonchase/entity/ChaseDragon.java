package dimblend.dragonchase.entity;

import dimblend.dragonchase.DragonChaseRegistries;
import dimblend.dragonchase.api.DragonChaseTuning;
import dimblend.dragonchase.chase.ChaseDirector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The remade ender dragon of the chase (requirements.md §3.2 prologue, §3.4 chase, §3.6 death).
 *
 * <p>Extends vanilla {@link EnderDragon} only to inherit the model, renderer, wing-flap animation,
 * growl/flap/hurt/death sounds and the part layout. No vanilla behaviour runs: {@link #aiStep()} is
 * a full replacement, so there is no {@code DragonPhaseManager} tick, no {@code checkWalls} block
 * destruction, no 32-block crystal scan, no shove/damage of touching entities and no
 * {@code EndDragonFight} traffic (vanilla equivalents:
 * {@code E:\misc\mcsrc-1.21.1\net\minecraft\world\entity\boss\enderdragon\EnderDragon.java:174-411},
 * {@code :550-589}).
 *
 * <p>Vanilla's constructor hard-codes the entity type ({@code EnderDragon.java:92}), so
 * {@link #getType()} is overridden to keep save/load and the renderer lookup on
 * {@code dimblend_dragonchase:chase_dragon}. {@link #createAttributes()} is registered for the same
 * reason: {@code DefaultAttributes.validate} requires a supplier for every living entity type.
 *
 * <p>Server thread only for world mutation; all director calls ({@link ChaseDragonHandle}) run from
 * the server tick.
 */
public class ChaseDragon extends EnderDragon implements ChaseDragonHandle {
    private static final int PART_HEAD = 0;
    private static final int PART_NECK = 1;
    private static final int PART_BODY = 2;
    private static final int PART_TAIL1 = 3;
    private static final int PART_WING1 = 6;
    private static final int PART_WING2 = 7;

    /**
     * §3.4 / decision dragon-position: flight sits 32 blocks above the nest, sways in z by +-32 and
     * bobs in y by +-16. Public because the GameTest pins the exact spec numbers.
     */
    public static final double FOLLOW_HEIGHT = 32.0D;
    public static final double FOLLOW_SWAY_AMPLITUDE = 32.0D;
    public static final double FOLLOW_BOB_AMPLITUDE = 16.0D;
    /** Prologue orbit: angular step per tick, flight budget and the anti-jump feed-forward cap. */
    private static final double ORBIT_ANGULAR_STEP = 0.012D;
    private static final float ORBIT_TURN_DEGREES_PER_TICK = 3.0F;
    private static final double ORBIT_MAX_VERTICAL_SPEED = 0.5D;
    private static final double ORBIT_ACCELERATION = 0.06D;
    /** Above the chase's fastest anchor (64 m/s): a bigger centre jump is a re-anchor, not motion. */
    private static final double ORBIT_MAX_FEED_FORWARD = 3.5D;
    /** Prologue departure: alignment window, flight speed and climb budget. */
    private static final float DEPART_ALIGN_DEGREES = 20.0F;
    private static final double DEPART_SPEED = 1.2D;
    private static final double DEPART_MAX_VERTICAL_SPEED = 1.2D;
    private static final double DEPART_ACCELERATION = 0.08D;
    /**
     * Fireball retreat: hover height over the track (above {@code landingY - 1}, i.e. y = 84 for the
     * default track) and how fast the dragon pulls back relative to the fleeing train.
     */
    public static final double FIREBALL_RETREAT_HEIGHT = 20.0D;
    public static final double FIREBALL_RETREAT_SPEED = 2.4D;
    public static final double FIREBALL_MAX_VERTICAL_SPEED = 1.6D;
    public static final double FIREBALL_RETREAT_ACCELERATION = 0.35D;
    public static final int FIREBALL_RETREAT_TIMEOUT = 100;
    /**
     * Breath: low hover over the track (nest top is trackY + 6, the dragon is 8 tall -> trackY + 10
     * clears the structure while counting as "lowest point above the nest", §3.4).
     */
    public static final double BREATH_HOVER_HEIGHT = 10.0D;
    public static final double BREATH_RETREAT_SPEED = 2.2D;
    public static final double BREATH_MAX_VERTICAL_SPEED = 1.4D;
    public static final double BREATH_ACCELERATION = 0.35D;
    public static final int BREATH_APPROACH_TIMEOUT = 100;
    /** ~3 s of breathing (requirements.md §3.4). */
    public static final int BREATH_TICKS = 60;
    /** Catch-up: surplus over the nest's own speed, its ramp and the arrival window. */
    public static final double CATCH_UP_SPEED_SURPLUS = 0.9D;
    public static final double CATCH_UP_ACCELERATION = 0.08D;
    public static final double CATCH_UP_ARRIVAL_X = 8.0D;
    /** Catch-up floor so even a slow nest is closed on instead of matched. */
    private static final double CATCH_UP_MIN_SPEED = 1.4D;
    /** Horizontal radius (blocks) in which the breath stream is sent, beyond vanilla's 32-block cull. */
    private static final double BREATH_PARTICLE_RADIUS = 256.0D;
    private static final int CRYSTAL_RESYNC_INTERVAL = 100;

    private final DamageBudget damageBudget = new DamageBudget();
    private final ServerBossEvent bossBar;
    private final List<ServerPlayer> appliedViewers = new ArrayList<>();
    private final List<EndCrystal> healingCrystals = new ArrayList<>();
    /**
     * Alive crystals of the whole chase, synced by the director from
     * {@code DragonCarriages.healingCrystalCount}; unlike {@link #healingCrystals} it is not
     * limited to loaded chunks (requirements.md §3.4 decision crystal-heal: no range limit).
     */
    private int healingCrystalCount;
    private final double[][] partSnapshot = new double[8][3];
    private int[] sentCrystalIds = new int[0];

    private ChaseDragonHandle.Mode mode = ChaseDragonHandle.Mode.CHASE_FOLLOW;
    private long swaySeed;

    @Nullable
    private Vec3 orbitCenter;
    private double orbitTrackY;
    private double orbitRadius = 50.0D;
    private double orbitAngle;

    @Nullable
    private Vec3 nestCenter;
    @Nullable
    private Vec3 trainFront;
    @Nullable
    private Vec3 lastNestCenter;
    private Vec3 nestVelocity = Vec3.ZERO;
    @Nullable
    private Vec3 lastTrainFront;
    private Vec3 trainVelocity = Vec3.ZERO;
    @Nullable
    private Vec3 lastOrbitCenter;
    private Vec3 orbitVelocity = Vec3.ZERO;

    /** {@code CHASE_FIREBALL}: ticks spent retreating, and the shot it was planned from. */
    private int retreatTicks;
    private double fireballBackOffset = 12.0D;
    private double fireballLandingY = 65.0D;

    private int breathTicks;
    private boolean breathApproached;
    private double breathTrackY;
    /**
     * Fallback breath segment, set only while there is no nest anchor at all (a caller that never
     * sent {@link #setChaseAnchors}); {@link #breatheOnto} clears it as soon as it has an anchor,
     * because the span is then re-derived every tick and rides the nest.
     */
    @Nullable
    private Vec3 breathFrom;
    @Nullable
    private Vec3 breathTo;

    private boolean bossBarShown;
    private int clientGrowlTime = 100;
    private int crystalSyncAge;
    private long lastTicketChunk = Long.MIN_VALUE;

    public ChaseDragon(EntityType<? extends EnderDragon> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.bossBar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        this.bossBar.setDarkenScreen(false);
        this.bossBar.setPlayBossMusic(false);
        this.bossBar.setCreateWorldFog(false);
        this.bossBar.setVisible(false);
        this.swaySeed = this.getUUID().getMostSignificantBits();
        this.setHealth(this.getMaxHealth());
    }

    public static ChaseDragon createPrologue(ServerLevel level, Vec3 pos) {
        ChaseDragon dragon = new ChaseDragon(DragonChaseRegistries.CHASE_DRAGON.get(), level);
        dragon.mode = ChaseDragonHandle.Mode.PROLOGUE_ORBIT;
        dragon.orbitCenter = pos;
        dragon.lastOrbitCenter = pos;
        dragon.orbitTrackY = pos.y - 56.0D;
        dragon.orbitRadius = 40.0D + Math.floorMod((int) (dragon.swaySeed ^ (dragon.swaySeed >>> 32)), 21);
        dragon.orbitAngle = (double) dragon.getRandom().nextFloat() * Math.PI * 2.0D;
        // Start on the circle, nose along the tangent: spawning at the centre with yaw 90 sent the
        // dragon charging sideways across its own orbit on tick one.
        Vec3 start = ChaseDragonFlight.orbitPoint(pos, dragon.orbitRadius, dragon.orbitAngle, pos.y);
        dragon.setPos(start.x, start.y, start.z);
        dragon.initHeading(ChaseDragonFlight.orbitTangentYaw(dragon.orbitAngle));
        return dragon;
    }

    public static ChaseDragon createChase(ServerLevel level, Vec3 pos, float maxHealth) {
        ChaseDragon dragon = new ChaseDragon(DragonChaseRegistries.CHASE_DRAGON.get(), level);
        dragon.mode = ChaseDragonHandle.Mode.CHASE_FOLLOW;
        dragon.setPos(pos.x, pos.y, pos.z);
        AttributeInstance attribute = dragon.getAttribute(Attributes.MAX_HEALTH);
        if (attribute != null) {
            attribute.setBaseValue(Math.max(1.0D, (double) maxHealth));
        }
        dragon.setHealth(dragon.getMaxHealth());
        dragon.initHeading(90.0F);
        return dragon;
    }

    /**
     * One-time heading setup before the first tick. The renderer's 64-entry yaw ring buffer is
     * seeded from {@code getYRot()} on the first {@code pushPositionHistory()}, so all four yaw
     * fields must agree here; afterwards heading only ever changes through
     * {@link ChaseDragonFlight#smoothTurn}.
     */
    private void initHeading(float yaw) {
        this.setYRot(yaw);
        this.yRotO = yaw;
        this.yBodyRot = yaw;
        this.yBodyRotO = yaw;
        this.yRotA = 0.0F;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return EnderDragon.createAttributes();
    }

    // ------------------------------------------------------------------------------------------
    // Contract
    // ------------------------------------------------------------------------------------------

    @Override
    public UUID uuid() {
        return this.getUUID();
    }

    @Override
    public ChaseDragonHandle.Mode mode() {
        return this.mode;
    }

    @Override
    public boolean isAliveAndLoaded() {
        if (this.isRemoved() || !this.isAlive()) {
            return false;
        }
        if (this.level().isClientSide) {
            return true;
        }
        // Never getChunk(..., false) here: that parks the server thread (skill §8.28).
        return this.level().getChunkSource().getChunkNow(this.chunkPosition().x, this.chunkPosition().z) != null;
    }

    @Override
    public void setOrbitCenter(Vec3 center, double trackY) {
        if (center == null) {
            return;
        }
        this.orbitCenter = center;
        this.orbitTrackY = trackY;
    }

    @Override
    public void depart() {
        if (this.level().isClientSide || this.mode != ChaseDragonHandle.Mode.PROLOGUE_ORBIT) {
            return;
        }
        // No yaw snap: tickDepart sweeps the nose round to +X over the next few dozen ticks.
        this.mode = ChaseDragonHandle.Mode.PROLOGUE_DEPART;
    }

    @Override
    public void setChaseAnchors(Vec3 nestCenter, Vec3 trainFront) {
        this.nestCenter = nestCenter;
        this.trainFront = trainFront;
    }

    /**
     * §3.4: accept the shot. Without a nest anchor (a bare dragon) it fires straight at the target;
     * with one it enters {@link ChaseDragonHandle.Mode#CHASE_FIREBALL}, pulls back to the train
     * front and fires from there (see {@link #tickFireballRetreat()}). The heading is never touched.
     */
    @Override
    public boolean fireFireballAt(Vec3 target) {
        if (this.level().isClientSide || target == null || !this.canStartChaseAction()) {
            return false;
        }
        if (this.nestCenter == null) {
            return this.shootFireballAt(target);
        }
        this.fireballBackOffset = Math.max(8.0D, this.nestCenter.x - target.x);
        this.fireballLandingY = target.y;
        this.mode = ChaseDragonHandle.Mode.CHASE_FIREBALL;
        this.retreatTicks = 0;
        return true;
    }

    /** Spawns one {@code ChaseFireball} from the mouth toward {@code target}; no heading change. */
    private boolean shootFireballAt(Vec3 target) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        Vec3 mouth = this.mouthPosition();
        ChaseEntityTickets.keepTicking(serverLevel, mouth);
        ChaseEntityTickets.keepTicking(serverLevel, target);
        ChaseFireball fireball = ChaseFireball.spawn(serverLevel, this, mouth, target, ChaseFireball.TRAVEL_TICKS);
        if (!serverLevel.addFreshEntity(fireball)) {
            return false;
        }
        this.level()
                .playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENDER_DRAGON_SHOOT, this.getSoundSource(),
                        3.0F, 1.0F);
        return true;
    }

    /**
     * §3.4: accept the breath. Without a nest anchor the recorded segment is used as-is; with one
     * the dragon retreats to the 50% station and sprays the span derived from it (see
     * {@link #tickBreath()}).
     */
    @Override
    public boolean breatheOnto(Vec3 trackFrom, Vec3 trackTo) {
        if (this.level().isClientSide || trackFrom == null || trackTo == null || !this.canStartChaseAction()) {
            return false;
        }
        this.mode = ChaseDragonHandle.Mode.CHASE_BREATH;
        this.breathApproached = this.nestCenter == null;
        this.breathTicks = 0;
        this.breathTrackY = trackFrom.y;
        // With an anchor the span is re-derived from the station every tick; only a bare dragon
        // keeps the recorded segment. That also marks "no anchor yet" after a reload: a chase save
        // carries no segment, so a first tick without anchors holds instead of spraying stale coords.
        this.breathFrom = this.nestCenter == null ? trackFrom : null;
        this.breathTo = this.nestCenter == null ? trackTo : null;
        return true;
    }

    @Override
    public void setBossBarName(@Nullable Component name) {
        if (this.level().isClientSide) {
            return;
        }
        if (name == null) {
            this.bossBarShown = false;
            this.bossBar.setVisible(false);
            this.bossBar.removeAllPlayers();
            this.appliedViewers.clear();
            return;
        }
        this.bossBar.setName(name);
        this.bossBarShown = true;
        this.bossBar.setVisible(true);
    }

    @Override
    public void setBossBarViewers(Collection<ServerPlayer> players) {
        if (this.level().isClientSide || players == null) {
            return;
        }
        // The director calls this every tick with the same players: compare by instance first so
        // the steady state allocates nothing (a respawn swaps the ServerPlayer instance).
        boolean same = players.size() == this.appliedViewers.size();
        if (same) {
            int index = 0;
            for (ServerPlayer player : players) {
                if (this.appliedViewers.get(index++) != player) {
                    same = false;
                    break;
                }
            }
        }
        if (same) {
            return;
        }
        this.appliedViewers.clear();
        this.appliedViewers.addAll(players);
        for (ServerPlayer existing : List.copyOf(this.bossBar.getPlayers())) {
            if (!players.contains(existing)) {
                this.bossBar.removePlayer(existing);
            }
        }
        for (ServerPlayer player : players) {
            if (!this.bossBar.getPlayers().contains(player)) {
                this.bossBar.addPlayer(player);
            }
        }
    }

    @Override
    public void setHealingCrystals(List<EndCrystal> crystals) {
        List<EndCrystal> incoming = crystals == null ? List.of() : crystals;
        // The director re-sends the same crystals every tick; skip identical lists so neither the
        // entity nor the network sees work in the steady state.
        if (sameCrystals(incoming)) {
            return;
        }
        this.healingCrystals.clear();
        this.healingCrystals.addAll(incoming);
        this.syncCrystalIds(true);
    }

    private boolean sameCrystals(List<EndCrystal> crystals) {
        if (crystals.size() != this.healingCrystals.size()) {
            return false;
        }
        int index = 0;
        for (EndCrystal crystal : crystals) {
            if (this.healingCrystals.get(index++) != crystal) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setHealingCrystalCount(int count) {
        this.healingCrystalCount = Math.max(0, count);
    }

    @Override
    public void discardSilently() {
        if (!this.level().isClientSide) {
            this.bossBar.setVisible(false);
            this.bossBar.removeAllPlayers();
        }
        this.discard();
    }

    // ------------------------------------------------------------------------------------------
    // Identity / resistance
    // ------------------------------------------------------------------------------------------

    /**
     * Vanilla's constructor ignores its type argument, so the type is replaced here to keep NBT
     * ({@code getEncodeId}), the client entity factory and the renderer lookup on our own type.
     */
    @Override
    public EntityType<?> getType() {
        EntityType<ChaseDragon> own = DragonChaseRegistries.chaseDragonType();
        return own != null ? own : super.getType();
    }

    /** §3.4: immune to fire, fall, suffocation and void damage; crystal explosions still hurt. */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return super.isInvulnerableTo(source)
                || source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypes.IN_WALL)
                || source.is(DamageTypes.FELL_OUT_OF_WORLD);
    }

    @Override
    public void checkDespawn() {
        // Never despawns (§3.4).
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Damage (§3.4 decision damage-pacing, §3.2 any damage)
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return this.applyIncomingDamage(source, amount);
    }

    /** Every part takes full damage: no head/body 1/4 split. */
    @Override
    public boolean hurt(EnderDragonPart part, DamageSource source, float amount) {
        return this.applyIncomingDamage(source, amount);
    }

    private boolean applyIncomingDamage(DamageSource source, float amount) {
        if (this.level().isClientSide || this.mode == ChaseDragonHandle.Mode.DYING || !this.isAlive()) {
            return false;
        }
        if (amount <= 0.0F || this.isInvulnerableTo(source)) {
            return false;
        }
        float cap = this.getMaxHealth() * (float) (DragonChaseTuning.damageCapPercent() / 100.0D);
        // Kill-type damage (generic_kill, out_of_world: data/minecraft/tags/damage_type/
        // bypasses_invulnerability.json) must never be paced away, otherwise /kill and the
        // debug command would leave the dragon alive for a full second. out_of_world is already
        // filtered by isInvulnerableTo (void immunity, §3.4), so only intentional kills reach here.
        boolean instantKill = source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        float applied = instantKill
                ? Math.min(amount, this.getHealth())
                : this.damageBudget.accept(this.tickCount, amount, Math.max(0.0F, cap));

        // The hurt flash and hurt sound are client-side reactions to this packet; they play even
        // when the whole hit was discarded by the budget.
        this.hurtDuration = 10;
        this.hurtTime = this.hurtDuration;
        this.level().broadcastDamageEvent(this, source);

        if (applied > 0.0F) {
            this.setHealth(Math.max(0.0F, this.getHealth() - applied));
            ChaseDragons.listener().onDamageTaken(this, applied);
            if (this.getHealth() <= 0.0F) {
                this.startDying();
            }
        }
        return true;
    }

    private void startDying() {
        if (this.mode == ChaseDragonHandle.Mode.DYING) {
            return;
        }
        this.mode = ChaseDragonHandle.Mode.DYING;
        this.breathFrom = null;
        this.breathTo = null;
        this.setHealth(0.0F);
        this.setDeltaMovement(Vec3.ZERO);
        this.dragonDeathTime = 0;
        this.bossBar.setVisible(false);
        this.bossBar.removeAllPlayers();
        this.bossBarShown = false;
        ChaseDragons.listener().onKilled(this);
    }

    /** §3.6: the vanilla death animation and sounds, then removal. No XP, no drops, no gate. */
    @Override
    protected void tickDeath() {
        this.dragonDeathTime++;
        if (this.dragonDeathTime >= 180 && this.dragonDeathTime <= 200) {
            float dx = (this.random.nextFloat() - 0.5F) * 8.0F;
            float dy = (this.random.nextFloat() - 0.5F) * 4.0F;
            float dz = (this.random.nextFloat() - 0.5F) * 8.0F;
            this.level()
                    .addParticle(ParticleTypes.EXPLOSION_EMITTER, this.getX() + (double) dx, this.getY() + 2.0D + (double) dy,
                            this.getZ() + (double) dz, 0.0D, 0.0D, 0.0D);
        }
        if (this.level() instanceof ServerLevel) {
            if (this.dragonDeathTime == 1 && !this.isSilent()) {
                // 1028 = dragon death sound at the dragon (LevelRenderer.globalLevelEvent).
                this.level().globalLevelEvent(1028, this.blockPosition(), 0);
            }
        }
        this.move(MoverType.SELF, new Vec3(0.0D, 0.1D, 0.0D));
        if (this.dragonDeathTime >= 200 && this.level() instanceof ServerLevel) {
            this.bossBar.removeAllPlayers();
            this.discard();
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        boolean serverSide = !this.level().isClientSide;
        if (serverSide) {
            this.bossBar.setVisible(false);
            this.bossBar.removeAllPlayers();
            this.bossBarShown = false;
        }
        super.remove(reason);
        // I25: `/kill` never runs startDying()/onKilled() — vanilla EnderDragon.kill() calls
        // remove(KILLED) directly — so this funnel is the only "the dragon is gone" signal a chase
        // BGM gets. Chunk unloads use setRemoved(UNLOADED_TO_CHUNK) and never reach here, and a
        // prologue dragon must not end a chase's music.
        if (serverSide && reason.shouldDestroy() && endsChaseMusicOnRemoval()) {
            ChaseDirector.onChaseDragonRemoved(this.getUUID());
        }
    }

    /** True for the five modes of a spawned chase dragon; false for the prologue's two. */
    private boolean endsChaseMusicOnRemoval() {
        return switch (this.mode) {
            case PROLOGUE_ORBIT, PROLOGUE_DEPART -> false;
            case CHASE_FOLLOW, CHASE_FIREBALL, CHASE_BREATH, CHASE_CATCH_UP, DYING -> true;
        };
    }

    // ------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------

    @Override
    public void aiStep() {
        this.processFlappingMovement();
        if (this.level().isClientSide) {
            this.tickClientGrowl();
        } else if (this.level() instanceof ServerLevel serverLevel) {
            long chunkKey = this.chunkPosition().toLong();
            if (chunkKey != this.lastTicketChunk || this.tickCount % 10 == 0) {
                this.lastTicketChunk = chunkKey;
                ChaseEntityTickets.keepTicking(serverLevel, this);
            }
        }
        this.oFlapTime = this.flapTime;

        if (this.isDeadOrDying()) {
            // Vanilla EnderDragon.java:211-217 -- random explosion puffs while dying.
            float dx = (this.random.nextFloat() - 0.5F) * 8.0F;
            float dy = (this.random.nextFloat() - 0.5F) * 4.0F;
            float dz = (this.random.nextFloat() - 0.5F) * 8.0F;
            this.level()
                    .addParticle(ParticleTypes.EXPLOSION, this.getX() + (double) dx, this.getY() + 2.0D + (double) dy,
                            this.getZ() + (double) dz, 0.0D, 0.0D, 0.0D);
            return;
        }

        // Wing-flap speed, same formula as vanilla EnderDragon.java:227-236.
        Vec3 movement = this.getDeltaMovement();
        float flap = 0.2F / ((float) movement.horizontalDistance() * 10.0F + 1.0F);
        flap *= (float) Math.pow(2.0D, movement.y);
        if (this.inWall) {
            this.flapTime += flap * 0.5F;
        } else {
            this.flapTime += flap;
        }

        this.setYRot(Mth.wrapDegrees(this.getYRot()));
        this.pushPositionHistory();

        if (this.level().isClientSide) {
            if (this.lerpSteps > 0) {
                this.lerpPositionAndRotationStep(this.lerpSteps, this.lerpX, this.lerpY, this.lerpZ, this.lerpYRot, this.lerpXRot);
                this.lerpSteps--;
            }
        } else {
            this.tickHealing();
            this.tickServerFlight();
            this.tickBossBar();
        }

        this.yBodyRot = this.getYRot();
        this.updatePartPositions();
    }

    /** Vanilla position ring (EnderDragon.java:232-248); the renderer interpolates from it. */
    private void pushPositionHistory() {
        if (this.posPointer < 0) {
            for (int i = 0; i < this.positions.length; i++) {
                this.positions[i][0] = this.getYRot();
                this.positions[i][1] = this.getY();
            }
        }
        if (++this.posPointer == this.positions.length) {
            this.posPointer = 0;
        }
        this.positions[this.posPointer][0] = this.getYRot();
        this.positions[this.posPointer][1] = this.getY();
    }

    private void tickClientGrowl() {
        if (this.isSilent()) {
            return;
        }
        if (--this.clientGrowlTime < 0) {
            this.level()
                    .playLocalSound(this.getX(), this.getY(), this.getZ(), SoundEvents.ENDER_DRAGON_GROWL, this.getSoundSource(),
                            2.5F, 0.8F + this.random.nextFloat() * 0.3F, false);
            this.clientGrowlTime = 200 + this.random.nextInt(200);
        }
    }

    private void tickServerFlight() {
        this.nestVelocity = Vec3.ZERO;
        if (this.nestCenter != null) {
            if (this.lastNestCenter != null) {
                this.nestVelocity = this.nestCenter.subtract(this.lastNestCenter);
                this.lastNestCenter = this.nestCenter;
            } else {
                this.lastNestCenter = this.nestCenter;
            }
        } else {
            this.lastNestCenter = null;
        }
        if (this.trainFront != null) {
            if (this.lastTrainFront != null) {
                this.trainVelocity = this.trainFront.subtract(this.lastTrainFront);
                this.lastTrainFront = this.trainFront;
            } else {
                this.lastTrainFront = this.trainFront;
            }
        } else {
            this.trainVelocity = Vec3.ZERO;
            this.lastTrainFront = null;
        }
        if (this.orbitCenter != null) {
            if (this.lastOrbitCenter != null) {
                this.orbitVelocity = this.orbitCenter.subtract(this.lastOrbitCenter);
                double length = this.orbitVelocity.length();
                if (length > ORBIT_MAX_FEED_FORWARD) {
                    // A re-anchored centre must not fling the dragon.
                    this.orbitVelocity = this.orbitVelocity.scale(ORBIT_MAX_FEED_FORWARD / length);
                }
                this.lastOrbitCenter = this.orbitCenter;
            } else {
                this.lastOrbitCenter = this.orbitCenter;
            }
        } else {
            this.orbitVelocity = Vec3.ZERO;
            this.lastOrbitCenter = null;
        }
        switch (this.mode) {
            case PROLOGUE_ORBIT -> this.tickOrbit();
            case PROLOGUE_DEPART -> this.tickDepart();
            case CHASE_FOLLOW -> this.tickFollow();
            case CHASE_FIREBALL -> this.tickFireballRetreat();
            case CHASE_BREATH -> this.tickBreath();
            case CHASE_CATCH_UP -> this.tickCatchUp();
            case DYING -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Flight modes
    // ------------------------------------------------------------------------------------------

    /**
     * Counter-clockwise circle around the prologue centre.
     *
     * <p>The velocity is the analytic circle tangent in the centre's frame plus the anchor's own
     * motion ({@code orbitVelocity}) and a small radial correction that holds the radius, so the
     * dragon tracks the circle exactly even while the train runs at 64 m/s. The heading follows
     * that velocity through the damped steering: aiming the nose at the raw tangent instead would
     * leave the model flying its circle sideways (3.2 against 0.7 blocks per tick is a 77 degree
     * crab), and aiming at a look-ahead point on the circle biases the nose inward by half the
     * look-ahead angle, which slowly spirals the orbit.
     */
    private void tickOrbit() {
        if (this.orbitCenter == null) {
            this.orbitCenter = this.position();
            this.orbitTrackY = this.getY() - 56.0D;
        }
        // 48..64 blocks above the track (decision prologue-height): centre 56, bob +-8.
        double altitude = this.orbitTrackY + 56.0D + ChaseDragonFlight.bobY(this.tickCount, this.swaySeed + 17L, 8.0D);
        this.orbitAngle = ChaseDragonFlight.wrapAngle(this.orbitAngle + ORBIT_ANGULAR_STEP);

        double circleSpeed = ORBIT_ANGULAR_STEP * this.orbitRadius;
        double dx = this.getX() - this.orbitCenter.x;
        double dz = this.getZ() - this.orbitCenter.z;
        double distance = Math.max(Math.sqrt(dx * dx + dz * dz), 1.0E-6D);
        double radial = Mth.clamp((this.orbitRadius - distance) * 0.05D, -0.3D, 0.3D);
        double vertical = Mth.clamp(altitude - this.getY(), -ORBIT_MAX_VERTICAL_SPEED, ORBIT_MAX_VERTICAL_SPEED);
        Vec3 desired = new Vec3(
                -Math.sin(this.orbitAngle) * circleSpeed + radial * dx / distance + this.orbitVelocity.x,
                vertical,
                Math.cos(this.orbitAngle) * circleSpeed + radial * dz / distance + this.orbitVelocity.z);

        Vec3 horizontal = new Vec3(desired.x, 0.0D, desired.z);
        float targetYaw = horizontal.lengthSqr() > 1.0E-12D
                ? ChaseDragonFlight.facingYaw(horizontal)
                : ChaseDragonFlight.orbitTangentYaw(this.orbitAngle);
        ChaseDragonFlight.TurnStep turn = ChaseDragonFlight.smoothTurn(this.getYRot(), this.yRotA, targetYaw,
                ORBIT_TURN_DEGREES_PER_TICK);
        this.setYRot(turn.yaw());
        this.yRotA = turn.yawVelocity();

        this.applyVelocity(ChaseDragonFlight.approach(this.getDeltaMovement(), desired, ORBIT_ACCELERATION));
    }

    /**
     * §3.2: sweep the nose round to +X while still flying along it, then climb at 45 degrees until
     * y = 512. Until the nose is within 20 degrees of +X the horizontal velocity follows the nose,
     * so the dragon leaves the circle in an arc instead of being dragged sideways.
     */
    private void tickDepart() {
        double climbStartY = this.orbitTrackY + 32.0D;
        ChaseDragonFlight.TurnStep turn = ChaseDragonFlight.smoothTurn(this.getYRot(), this.yRotA, 90.0F,
                ChaseDragonFlight.MAX_TURN_DEGREES_PER_TICK);
        this.setYRot(turn.yaw());
        this.yRotA = turn.yawVelocity();

        Vec3 desired;
        if (Math.abs(Mth.wrapDegrees(90.0F - this.getYRot())) > DEPART_ALIGN_DEGREES) {
            float yawRad = this.yawRadians();
            Vec3 forward = new Vec3((double) Mth.sin(yawRad), 0.0D, (double) -Mth.cos(yawRad));
            double targetZ = this.orbitCenter != null ? this.orbitCenter.z : this.getZ();
            double lateral = Mth.clamp((targetZ - this.getZ()) * 0.05D, -0.5D, 0.5D);
            double vertical = Mth.clamp((climbStartY - this.getY()) * 0.08D, -DEPART_MAX_VERTICAL_SPEED,
                    DEPART_MAX_VERTICAL_SPEED);
            desired = new Vec3(forward.x * DEPART_SPEED, vertical, forward.z * DEPART_SPEED + lateral);
        } else {
            Vec3 waypoint = ChaseDragonFlight.departWaypoint(this.position(), climbStartY);
            desired = ChaseDragonFlight.desiredVelocity(waypoint.subtract(this.position()), Vec3.ZERO, DEPART_SPEED,
                    DEPART_MAX_VERTICAL_SPEED);
        }
        this.applyVelocity(ChaseDragonFlight.approach(this.getDeltaMovement(), desired, DEPART_ACCELERATION));
        if (this.getY() >= ChaseDragonFlight.DEPART_TARGET_Y) {
            ChaseDragons.listener().onDeparted(this);
            this.discard();
        }
    }

    private void tickFollow() {
        if (this.nestCenter == null) {
            return;
        }
        this.steerToward(this.followTarget(), this.nestVelocity, ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED,
                ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED, ChaseDragonFlight.FOLLOW_ACCELERATION);
        this.faceChaseDirection();
    }

    /**
     * §3.4: pull back to just ahead of the train front, then fire at the predicted track point.
     * The heading stays forward the whole way ({@link ChaseDragonFlight#chaseForwardYaw}).
     */
    private void tickFireballRetreat() {
        if (this.nestCenter == null) {
            // Anchors arrive in LevelTickEvent.Post after the first entity tick on reload; keep the
            // saved manoeuvre intact instead of dropping the already-paid fireball.
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        double goalX = ChaseDragonFlight.fireballRetreatX(this.nestCenter.x, this.trainFrontX());
        Vec3 goal = new Vec3(goalX, this.fireballLandingY - 1.0D + FIREBALL_RETREAT_HEIGHT, this.nestCenter.z);
        this.steerRelative(goal, this.attackFeedForward(), FIREBALL_RETREAT_SPEED, FIREBALL_MAX_VERTICAL_SPEED,
                FIREBALL_RETREAT_ACCELERATION);
        this.faceChaseDirection();

        this.retreatTicks++;
        boolean arrived = this.getX() <= goalX + 4.0D
                && Math.abs(this.getZ() - this.nestCenter.z) <= 8.0D;
        if (arrived || this.retreatTicks >= FIREBALL_RETREAT_TIMEOUT) {
            // The landing point the shot was planned from, kept at least 16 blocks ahead so an
            // extreme close-range shot still travels +X.
            double impactX = Math.max(this.nestCenter.x - this.fireballBackOffset, this.getX() + 16.0D);
            this.shootFireballAt(new Vec3(impactX, this.fireballLandingY, 0.0D));
            this.mode = ChaseDragonHandle.Mode.CHASE_CATCH_UP;
        }
    }

    /**
     * §3.4: retreat at low altitude to the 50% station, hold it while spraying the track for 3 s,
     * then catch back up. Phase 1 only flies; phase 2 sprays and re-derives the span every tick.
     */
    private void tickBreath() {
        if (this.nestCenter == null) {
            if (this.breathFrom == null) {
                // Anchors arrive in LevelTickEvent.Post after the first entity tick on reload; hold
                // the saved manoeuvre rather than spray a stale segment or burn its tick budget.
                this.setDeltaMovement(Vec3.ZERO);
                return;
            }
            // A bare dragon (no nest anchor ever sent): spray the recorded segment as-is.
            this.setDeltaMovement(Vec3.ZERO);
            this.breathTicks++;
            if (this.breathTicks % 2 == 0) {
                this.emitBreathParticles();
            }
            if (this.breathTicks >= BREATH_TICKS) {
                this.finishBreath();
            }
            return;
        }
        double stationX = ChaseDragonFlight.breathRetreatX(this.nestCenter.x, this.trainFrontX());
        Vec3 hover = new Vec3(stationX, this.nestCenter.y + BREATH_HOVER_HEIGHT, this.nestCenter.z);
        // The station is half of the nest + train span, so it moves at half of their combined speed;
        // without a train anchor it is a fixed offset from the nest and moves with the nest.
        Vec3 feedForward = this.trainFront != null
                ? this.nestVelocity.add(this.trainVelocity).scale(0.5D)
                : this.nestVelocity;
        this.steerRelative(hover, feedForward, BREATH_RETREAT_SPEED, BREATH_MAX_VERTICAL_SPEED, BREATH_ACCELERATION);
        this.faceChaseDirection();
        this.breathTicks++;

        if (!this.breathApproached) {
            boolean arrived = Math.abs(this.getX() - stationX) <= 5.0D
                    && Math.abs(this.getY() - hover.y) <= 4.0D;
            if (arrived || this.breathTicks >= BREATH_APPROACH_TIMEOUT) {
                this.breathApproached = true;
                this.breathTicks = 0;
            }
            return;
        }
        if (this.breathTicks % 2 == 0) {
            this.emitBreathParticles();
        }
        if (this.breathTicks >= BREATH_TICKS) {
            this.finishBreath();
        }
    }

    private void finishBreath() {
        ChaseDragons.listener().onBreathFinished(this, this.liveBreathFrom(), this.liveBreathTo());
        this.breathApproached = false;
        this.breathTicks = 0;
        this.mode = ChaseDragonHandle.Mode.CHASE_CATCH_UP;
    }

    /**
     * §3.4: after a shot or a breath, fly the follow point again but with a small permanent speed
     * surplus, so the gap closes steadily instead of the dragon teleporting back.
     */
    private void tickCatchUp() {
        if (this.nestCenter == null) {
            // Anchors arrive in LevelTickEvent.Post after the first entity tick on reload; hold the
            // saved mode so the catch-up resumes instead of being demoted to an anchorless follow.
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        double maxSpeed = Math.max(CATCH_UP_MIN_SPEED, this.nestVelocity.horizontalDistance() + CATCH_UP_SPEED_SURPLUS);
        // Brake any residual pullback velocity promptly so the dragon does not drift 30+ blocks
        // behind the locomotive cab after firing, then build the forward surplus gradually.
        double accel = this.getDeltaMovement().x < this.nestVelocity.x
                ? FIREBALL_RETREAT_ACCELERATION
                : CATCH_UP_ACCELERATION;
        this.steerToward(this.followTarget(), this.nestVelocity, maxSpeed, ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED,
                accel);
        this.faceChaseDirection();
        if (Math.abs(this.getX() - this.nestCenter.x) <= CATCH_UP_ARRIVAL_X) {
            this.mode = ChaseDragonHandle.Mode.CHASE_FOLLOW;
        }
    }

    /** The follow hover point, shared by CHASE_FOLLOW and CHASE_CATCH_UP so they join seamlessly. */
    private Vec3 followTarget() {
        if (this.nestCenter == null) {
            return this.position();
        }
        double sway = ChaseDragonFlight.swayZ(this.tickCount, this.swaySeed, FOLLOW_SWAY_AMPLITUDE);
        double bob = ChaseDragonFlight.bobY(this.tickCount, this.swaySeed + 31L, FOLLOW_BOB_AMPLITUDE);
        return new Vec3(this.nestCenter.x, this.nestCenter.y + FOLLOW_HEIGHT + bob, this.nestCenter.z + sway);
    }

    /** Train-front X reference; the fallback gap is the tuning's own keep-away distance. */
    private double trainFrontX() {
        return this.trainFront != null
                ? this.trainFront.x
                : this.nestCenter != null ? this.nestCenter.x - DragonChaseTuning.keepAwayDistance() : this.getX();
    }

    /** Speed feed-forward for an attack station: the train when known, else the nest itself. */
    private Vec3 attackFeedForward() {
        return this.trainFront != null ? this.trainVelocity : this.nestVelocity;
    }

    /** Track span start: re-derived from the live station, or the recorded fallback without anchors. */
    private Vec3 liveBreathFrom() {
        if (this.nestCenter == null) {
            return this.breathFrom != null ? this.breathFrom : this.position();
        }
        return new Vec3(this.liveBreathZone().fromX(), this.breathTrackY, 0.0D);
    }

    /** Track span end, re-derived like {@link #liveBreathFrom()}. */
    private Vec3 liveBreathTo() {
        if (this.nestCenter == null) {
            return this.breathTo != null ? this.breathTo : this.position();
        }
        return new Vec3(this.liveBreathZone().toX(), this.breathTrackY, 0.0D);
    }

    private ChaseDragonFlight.BreathZone liveBreathZone() {
        double nestX = this.nestCenter != null ? this.nestCenter.x : this.getX();
        // Phase 1 can time out before the dragon reaches the station (a crawling train), and the
        // station then sits behind its jaws. Clamping it to the mouth's own X keeps the stream
        // ahead of the dragon instead of spraying backwards; the nest-side clamps still apply.
        double stationX = Math.max(ChaseDragonFlight.breathRetreatX(nestX, this.trainFrontX()), this.getX());
        return ChaseDragonFlight.breathZoneRange(nestX, stationX);
    }

    /** Follow-style steering: total horizontal speed capped (the cap already includes the anchor). */
    private void steerToward(Vec3 target, Vec3 feedForward, double maxHorizontalSpeed, double maxVerticalSpeed,
            double acceleration) {
        Vec3 desired = ChaseDragonFlight.desiredVelocity(target.subtract(this.position()), feedForward, maxHorizontalSpeed,
                maxVerticalSpeed);
        this.accelerate(desired, acceleration);
    }

    /** Retreat-style steering: the speed cap is relative to the anchor, so the dragon may back off. */
    private void steerRelative(Vec3 target, Vec3 anchorVelocity, double maxRelativeSpeed, double maxVerticalSpeed,
            double acceleration) {
        Vec3 desired = ChaseDragonFlight.retreatVelocity(target.subtract(this.position()), anchorVelocity,
                maxRelativeSpeed, maxVerticalSpeed);
        this.accelerate(desired, acceleration);
    }

    /**
     * Per-axis acceleration: horizontal and vertical each change by at most {@code acceleration}
     * per tick. A shared 3D step let a climb eat the horizontal budget, which made the dragon fall
     * 30 blocks behind while it was still climbing back to the follow altitude.
     */
    private void accelerate(Vec3 desired, double acceleration) {
        Vec3 current = this.getDeltaMovement();
        Vec3 horizontal = ChaseDragonFlight.approach(new Vec3(current.x, 0.0D, current.z),
                new Vec3(desired.x, 0.0D, desired.z), acceleration);
        double vertical = current.y + Mth.clamp(desired.y - current.y, -acceleration, acceleration);
        this.applyVelocity(new Vec3(horizontal.x, vertical, horizontal.z));
    }

    private void applyVelocity(Vec3 velocity) {
        this.setDeltaMovement(velocity);
        this.move(MoverType.SELF, velocity);
    }

    /**
     * Chase heading, damped: the nose tracks the forward corridor and only leans with the lateral
     * velocity, so it never swings round during a retreat.
     */
    private void faceChaseDirection() {
        float targetYaw = ChaseDragonFlight.chaseForwardYaw(this.getDeltaMovement());
        ChaseDragonFlight.TurnStep turn = ChaseDragonFlight.smoothTurn(this.getYRot(), this.yRotA, targetYaw,
                ChaseDragonFlight.MAX_TURN_DEGREES_PER_TICK);
        this.setYRot(turn.yaw());
        this.yRotA = turn.yawVelocity();
    }

    private float yawRadians() {
        return this.getYRot() * (float) (Math.PI / 180.0D);
    }

    /** Mouth of the model: about 7.5 blocks ahead of the centre, one block up. */
    public Vec3 mouthPosition() {
        float yawRad = this.yawRadians();
        return new Vec3(this.getX() + (double) Mth.sin(yawRad) * 7.5D, this.getY() + 1.0D,
                this.getZ() - (double) Mth.cos(yawRad) * 7.5D);
    }

    private void emitBreathParticles() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 from = this.liveBreathFrom();
        Vec3 to = this.liveBreathTo();
        Vec3 mouth = this.mouthPosition();
        double radiusSq = BREATH_PARTICLE_RADIUS * BREATH_PARTICLE_RADIUS;
        for (int i = 0; i < 3; i++) {
            double t = this.random.nextDouble();
            Vec3 landing = from.lerp(to, t).add(0.0D, 0.35D, 0.0D);
            for (int step = 1; step <= 3; step++) {
                Vec3 point = mouth.lerp(landing, (double) step / 4.0D);
                // sendParticles(level-wide) culls beyond 32 blocks, invisible across the 128-block
                // chase gap; overrideLimiter via the per-player overload reaches 256 blocks.
                for (ServerPlayer player : serverLevel.players()) {
                    double dx = player.getX() - point.x;
                    double dz = player.getZ() - point.z;
                    if (dx * dx + dz * dz <= radiusSq) {
                        serverLevel.sendParticles(player, ParticleTypes.DRAGON_BREATH, true, point.x, point.y, point.z,
                                1, 0.0D, 0.0D, 0.0D, 0.0D);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Healing, boss bar
    // ------------------------------------------------------------------------------------------

    private void tickHealing() {
        this.crystalSyncAge++;
        if (this.tickCount % 20 == 0) {
            // The synced count covers every crystal of the chase, loaded or not (§3.4: no range
            // limit); the entity list is only what the client needs for its beams and can be
            // shorter when a crystal car is outside our view. One health per crystal per second.
            int alive = 0;
            for (EndCrystal crystal : this.healingCrystals) {
                if (!crystal.isRemoved()) {
                    alive++;
                }
            }
            int healing = Math.max(alive, this.healingCrystalCount);
            if (healing > 0 && this.getHealth() < this.getMaxHealth()) {
                this.setHealth(Math.min(this.getMaxHealth(), this.getHealth() + (float) healing));
            }
        }
        if (this.crystalSyncAge >= CRYSTAL_RESYNC_INTERVAL) {
            this.syncCrystalIds(false);
        }
    }

    private void syncCrystalIds(boolean force) {
        if (this.level().isClientSide) {
            return;
        }
        int[] ids = new int[this.healingCrystals.size()];
        int count = 0;
        for (EndCrystal crystal : this.healingCrystals) {
            if (!crystal.isRemoved()) {
                ids[count++] = crystal.getId();
            }
        }
        ids = Arrays.copyOf(ids, count);
        if (!force && this.crystalSyncAge < CRYSTAL_RESYNC_INTERVAL && Arrays.equals(ids, this.sentCrystalIds)) {
            return;
        }
        this.crystalSyncAge = 0;
        this.sentCrystalIds = ids;
        PacketDistributor.sendToPlayersTrackingEntity(this, new CrystalBeams(this.getId(), ids));
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (!this.level().isClientSide && this.sentCrystalIds.length > 0) {
            PacketDistributor.sendToPlayer(player, new CrystalBeams(this.getId(), this.sentCrystalIds));
        }
    }

    private void tickBossBar() {
        if (!this.bossBarShown) {
            return;
        }
        float max = this.getMaxHealth();
        this.bossBar.setProgress(max <= 0.0F ? 0.0F : Mth.clamp(this.getHealth() / max, 0.0F, 1.0F));
    }

    // ------------------------------------------------------------------------------------------
    // Parts (vanilla EnderDragon.java:313-400, minus wall checks and shoving)
    // ------------------------------------------------------------------------------------------

    private void updatePartPositions() {
        EnderDragonPart[] parts = this.getSubEntities();
        for (int i = 0; i < parts.length; i++) {
            this.partSnapshot[i][0] = parts[i].getX();
            this.partSnapshot[i][1] = parts[i].getY();
            this.partSnapshot[i][2] = parts[i].getZ();
        }

        float pitch = (float) (this.getLatencyPos(5, 1.0F)[1] - this.getLatencyPos(10, 1.0F)[1]) * 10.0F
                * (float) (Math.PI / 180.0D);
        float cosPitch = Mth.cos(pitch);
        float sinPitch = Mth.sin(pitch);
        float yawRad = this.getYRot() * (float) (Math.PI / 180.0D);
        float sinYaw = Mth.sin(yawRad);
        float cosYaw = Mth.cos(yawRad);

        this.movePart(parts[PART_BODY], (double) (sinYaw * 0.5F), 0.0D, (double) (-cosYaw * 0.5F));
        this.movePart(parts[PART_WING1], (double) (cosYaw * 4.5F), 2.0D, (double) (sinYaw * 4.5F));
        this.movePart(parts[PART_WING2], (double) (cosYaw * -4.5F), 2.0D, (double) (sinYaw * -4.5F));

        float headYOffset = (float) (this.getLatencyPos(5, 1.0F)[1] - this.getLatencyPos(0, 1.0F)[1]);
        // Vanilla EnderDragon.java:330-331: the head and neck lead the body by a fraction of the
        // current yaw rate, so the dragon looks into its turn instead of sliding through it.
        float leadYaw = yawRad - this.yRotA * 0.01F;
        float sinLead = Mth.sin(leadYaw);
        float cosLead = Mth.cos(leadYaw);
        this.movePart(parts[PART_HEAD], (double) (sinLead * 6.5F * cosPitch), (double) (headYOffset + sinPitch * 6.5F),
                (double) (-cosLead * 6.5F * cosPitch));
        this.movePart(parts[PART_NECK], (double) (sinLead * 5.5F * cosPitch), (double) (headYOffset + sinPitch * 5.5F),
                (double) (-cosLead * 5.5F * cosPitch));

        double[] base = this.getLatencyPos(5, 1.0F);
        for (int k = 0; k < 3; k++) {
            double[] tail = this.getLatencyPos(12 + k * 2, 1.0F);
            float tailYaw = yawRad + (float) Mth.wrapDegrees(tail[0] - base[0]) * (float) (Math.PI / 180.0D);
            float sinTail = Mth.sin(tailYaw);
            float cosTail = Mth.cos(tailYaw);
            float span = (float) (k + 1) * 2.0F;
            this.movePart(parts[PART_TAIL1 + k], (double) (-(sinYaw * 1.5F + sinTail * span) * cosPitch),
                    tail[1] - base[1] - (double) ((span + 1.5F) * sinPitch) + 1.5D,
                    (double) ((cosYaw * 1.5F + cosTail * span) * cosPitch));
        }

        // Parts are not ticked by the level, so their previous positions are written here
        // (vanilla EnderDragon.java:387-396).
        for (int i = 0; i < parts.length; i++) {
            parts[i].xo = this.partSnapshot[i][0];
            parts[i].yo = this.partSnapshot[i][1];
            parts[i].zo = this.partSnapshot[i][2];
            parts[i].xOld = this.partSnapshot[i][0];
            parts[i].yOld = this.partSnapshot[i][1];
            parts[i].zOld = this.partSnapshot[i][2];
        }
    }

    private void movePart(EnderDragonPart part, double dx, double dy, double dz) {
        part.setPos(this.getX() + dx, this.getY() + dy, this.getZ() + dz);
    }

    // ------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("ChaseMode", this.mode.name());
        tag.putLong("ChaseSeed", this.swaySeed);
        if (this.orbitCenter != null) {
            tag.putDouble("OrbitX", this.orbitCenter.x);
            tag.putDouble("OrbitZ", this.orbitCenter.z);
            tag.putDouble("OrbitTrackY", this.orbitTrackY);
        }
        switch (this.mode) {
            case CHASE_FIREBALL -> {
                tag.putInt("RetreatTicks", this.retreatTicks);
                tag.putDouble("FireballBackOffset", this.fireballBackOffset);
                tag.putDouble("FireballLandingY", this.fireballLandingY);
            }
            case CHASE_BREATH -> {
                tag.putBoolean("BreathApproached", this.breathApproached);
                tag.putInt("BreathTicks", this.breathTicks);
                tag.putDouble("BreathTrackY", this.breathTrackY);
                // Only a bare dragon keeps a recorded segment; an anchored manoeuvre re-derives the
                // span from the nest every tick, and writing nothing is how a reload knows it must
                // wait for the anchors before spraying.
                if (this.nestCenter == null && this.breathFrom != null) {
                    tag.putDouble("BreathFromX", this.breathFrom.x);
                    tag.putDouble("BreathFromY", this.breathFrom.y);
                    tag.putDouble("BreathFromZ", this.breathFrom.z);
                }
                if (this.nestCenter == null && this.breathTo != null) {
                    tag.putDouble("BreathToX", this.breathTo.x);
                    tag.putDouble("BreathToY", this.breathTo.y);
                    tag.putDouble("BreathToZ", this.breathTo.z);
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("ChaseSeed")) {
            this.swaySeed = tag.getLong("ChaseSeed");
            this.orbitRadius = 40.0D + Math.floorMod((int) (this.swaySeed ^ (this.swaySeed >>> 32)), 21);
        }
        if (tag.contains("OrbitX") && tag.contains("OrbitZ")) {
            this.orbitCenter = new Vec3(tag.getDouble("OrbitX"), this.getY(), tag.getDouble("OrbitZ"));
            this.orbitTrackY = tag.getDouble("OrbitTrackY");
            this.lastOrbitCenter = this.orbitCenter;
        }
        this.breathApproached = false;
        this.breathTicks = 0;
        this.retreatTicks = 0;
        if (tag.contains("ChaseMode")) {
            ChaseDragonHandle.Mode saved;
            try {
                saved = ChaseDragonHandle.Mode.valueOf(tag.getString("ChaseMode"));
            } catch (IllegalArgumentException exception) {
                // Pre-rewrite saves carry the removed CHASE_SWOOP (and anything else unknown):
                // restart the chase cleanly instead of dropping the entity or throwing.
                saved = ChaseDragonHandle.Mode.CHASE_FOLLOW;
            }
            // Anchors are re-sent by the director every tick. Attack modes keep their progress so a
            // reload mid-manoeuvre still fires/sprays and then catches back up.
            switch (saved) {
                case CHASE_FIREBALL -> {
                    this.mode = saved;
                    this.retreatTicks = Math.max(0, tag.getInt("RetreatTicks"));
                    this.fireballBackOffset = tag.contains("FireballBackOffset")
                            ? tag.getDouble("FireballBackOffset")
                            : 12.0D;
                    this.fireballLandingY = tag.contains("FireballLandingY")
                            ? tag.getDouble("FireballLandingY")
                            : 65.0D;
                }
                case CHASE_BREATH -> {
                    this.mode = saved;
                    if (tag.contains("BreathFromX")) {
                        this.breathFrom = new Vec3(tag.getDouble("BreathFromX"), tag.getDouble("BreathFromY"),
                                tag.getDouble("BreathFromZ"));
                    }
                    if (tag.contains("BreathToX")) {
                        this.breathTo = new Vec3(tag.getDouble("BreathToX"), tag.getDouble("BreathToY"),
                                tag.getDouble("BreathToZ"));
                    }
                    this.breathTrackY = tag.contains("BreathTrackY")
                            ? tag.getDouble("BreathTrackY")
                            : this.breathFrom != null ? this.breathFrom.y : this.getY();
                    this.breathApproached = tag.getBoolean("BreathApproached");
                    this.breathTicks = Mth.clamp(tag.getInt("BreathTicks"), 0, BREATH_TICKS - 1);
                }
                case DYING, PROLOGUE_ORBIT, PROLOGUE_DEPART, CHASE_FOLLOW, CHASE_CATCH_UP -> this.mode = saved;
            }
        }
    }
}
