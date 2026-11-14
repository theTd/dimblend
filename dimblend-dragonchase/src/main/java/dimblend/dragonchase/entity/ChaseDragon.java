package dimblend.dragonchase.entity;

import dimblend.dragonchase.DragonChaseRegistries;
import dimblend.dragonchase.api.DragonChaseTuning;
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
    /** Below this distance to the hover point the breath dragon stops approaching. */
    private static final double BREATH_ARRIVAL_DISTANCE_SQ = 36.0D;
    private static final int BREATH_APPROACH_TIMEOUT = 140;
    /** ~3 s of breathing (requirements.md §3.4). */
    private static final int BREATH_TICKS = 60;
    /**
     * Tight hover height above the nest anchor (nest top is trackY + 6, the dragon is 8 tall ->
     * trackY + 10 clears the structure while counting as "lowest point above the nest", §3.4).
     */
    private static final double BREATH_HOVER_HEIGHT = 10.0D;
    /** Breath hover: minimum horizontal steering speed while riding the fleeing nest. */
    public static final double BREATH_MIN_HOVER_SPEED = 3.0D;
    /** Breath hover: surplus speed over the nest's own so the dragon can close the gap. */
    public static final double BREATH_SPEED_MARGIN = 1.6D;
    /** Breath hover: vertical steering cap. */
    public static final double BREATH_MAX_VERTICAL_SPEED = 1.4D;
    /** Breath hover: steering acceleration. */
    public static final double BREATH_ACCELERATION = 0.35D;
    /** Horizontal radius (blocks) in which the breath stream is sent, beyond vanilla's 32-block cull. */
    private static final double BREATH_PARTICLE_RADIUS = 256.0D;
    private static final int CRYSTAL_RESYNC_INTERVAL = 100;
    /** Ticks a fireball turn is held so the head visibly swings back toward the target. */
    private static final int FIRE_TURN_HOLD_TICKS = 20;

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

    private int swoopEndTick;
    private int breathTicks;
    private boolean breathApproached;
    /**
     * Breath segment as X offsets from the nest anchor: the nest flees 30-96+ blocks during the
     * 3 s breath, so the segment must ride with it instead of being frozen at the call position.
     */
    private double breathFromOffsetX;
    private double breathToOffsetX;
    private double breathTrackY;
    @Nullable
    private Vec3 breathFrom;
    @Nullable
    private Vec3 breathTo;

    private float fireTurnYaw;
    private int fireTurnTicks;

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
        dragon.setPos(pos.x, pos.y, pos.z);
        dragon.orbitCenter = pos;
        dragon.orbitTrackY = pos.y - 56.0D;
        dragon.orbitRadius = 40.0D + Math.floorMod((int) (dragon.swaySeed ^ (dragon.swaySeed >>> 32)), 21);
        dragon.orbitAngle = (double) dragon.getRandom().nextFloat() * Math.PI * 2.0D;
        dragon.setYRot(90.0F);
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
        dragon.setYRot(90.0F);
        return dragon;
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
        this.mode = ChaseDragonHandle.Mode.PROLOGUE_DEPART;
        this.setYRot(90.0F);
    }

    @Override
    public void setChaseAnchors(Vec3 nestCenter, Vec3 trainFront) {
        this.nestCenter = nestCenter;
        this.trainFront = trainFront;
    }

    @Override
    public boolean fireFireballAt(Vec3 target) {
        if (this.level().isClientSide || target == null) {
            return false;
        }
        if (this.mode == ChaseDragonHandle.Mode.DYING || this.mode == ChaseDragonHandle.Mode.CHASE_BREATH
                || this.mode == ChaseDragonHandle.Mode.PROLOGUE_ORBIT || this.mode == ChaseDragonHandle.Mode.PROLOGUE_DEPART
                || this.isDeadOrDying()) {
            return false;
        }
        Vec3 aim = target.subtract(this.position());
        this.fireTurnYaw = ChaseDragonFlight.facingYaw(aim);
        this.fireTurnTicks = FIRE_TURN_HOLD_TICKS;
        this.setYRot(this.fireTurnYaw);

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

    @Override
    public void beginSwoop(int ticks) {
        if (this.level().isClientSide || this.mode == ChaseDragonHandle.Mode.DYING
                || this.mode == ChaseDragonHandle.Mode.CHASE_BREATH
                || this.mode == ChaseDragonHandle.Mode.PROLOGUE_ORBIT
                || this.mode == ChaseDragonHandle.Mode.PROLOGUE_DEPART) {
            return;
        }
        this.mode = ChaseDragonHandle.Mode.CHASE_SWOOP;
        this.swoopEndTick = this.tickCount + Math.max(1, ticks);
    }

    @Override
    public void breatheOnto(Vec3 trackFrom, Vec3 trackTo) {
        if (this.level().isClientSide || this.mode == ChaseDragonHandle.Mode.DYING
                || this.mode == ChaseDragonHandle.Mode.CHASE_BREATH
                || this.mode == ChaseDragonHandle.Mode.PROLOGUE_ORBIT
                || this.mode == ChaseDragonHandle.Mode.PROLOGUE_DEPART) {
            return;
        }
        this.mode = ChaseDragonHandle.Mode.CHASE_BREATH;
        this.breathFrom = trackFrom;
        this.breathTo = trackTo;
        double anchorX = this.nestCenter != null ? this.nestCenter.x : this.getX();
        this.breathFromOffsetX = trackFrom.x - anchorX;
        this.breathToOffsetX = trackTo.x - anchorX;
        this.breathTrackY = trackFrom.y;
        this.breathTicks = 0;
        this.breathApproached = this.nestCenter == null;
        this.fireTurnTicks = 0;
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
        if (!this.level().isClientSide) {
            this.bossBar.setVisible(false);
            this.bossBar.removeAllPlayers();
            this.bossBarShown = false;
        }
        super.remove(reason);
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
        if (this.fireTurnTicks > 0) {
            this.fireTurnTicks--;
        }
        this.nestVelocity = Vec3.ZERO;
        if (this.nestCenter != null) {
            if (this.lastNestCenter != null) {
                this.nestVelocity = this.nestCenter.subtract(this.lastNestCenter);
            }
            this.lastNestCenter = this.nestCenter;
        }
        switch (this.mode) {
            case PROLOGUE_ORBIT -> this.tickOrbit();
            case PROLOGUE_DEPART -> this.tickDepart();
            case CHASE_FOLLOW -> this.tickFollow();
            case CHASE_SWOOP -> this.tickSwoop();
            case CHASE_BREATH -> this.tickBreath();
            case DYING -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Flight modes
    // ------------------------------------------------------------------------------------------

    private void tickOrbit() {
        if (this.orbitCenter == null) {
            this.orbitCenter = this.position();
            this.orbitTrackY = this.getY() - 56.0D;
        }
        // 48..64 blocks above the track (decision prologue-height): centre 56, bob +-8.
        double altitude = this.orbitTrackY + 56.0D + ChaseDragonFlight.bobY(this.tickCount, this.swaySeed + 17L, 8.0D);
        this.orbitAngle = ChaseDragonFlight.wrapAngle(this.orbitAngle + 0.012D);
        Vec3 target = ChaseDragonFlight.orbitPoint(this.orbitCenter, this.orbitRadius, this.orbitAngle, altitude);
        this.steerToward(target, Vec3.ZERO, 1.4D, 0.5D, 0.06D);
        this.faceVelocityOr(90.0F, 4.0F);
    }

    private void tickDepart() {
        double climbStartY = this.orbitTrackY + 32.0D;
        Vec3 waypoint = ChaseDragonFlight.departWaypoint(this.position(), climbStartY);
        this.steerToward(waypoint, Vec3.ZERO, 1.2D, 1.2D, 0.08D);
        this.setYRot(ChaseDragonFlight.turnToward(this.getYRot(), 90.0F, 4.0F));
        if (this.getY() >= ChaseDragonFlight.DEPART_TARGET_Y) {
            ChaseDragons.listener().onDeparted(this);
            this.discard();
        }
    }

    private void tickFollow() {
        if (this.nestCenter == null) {
            return;
        }
        double sway = ChaseDragonFlight.swayZ(this.tickCount, this.swaySeed, FOLLOW_SWAY_AMPLITUDE);
        double bob = ChaseDragonFlight.bobY(this.tickCount, this.swaySeed + 31L, FOLLOW_BOB_AMPLITUDE);
        Vec3 target = new Vec3(this.nestCenter.x, this.nestCenter.y + FOLLOW_HEIGHT + bob, this.nestCenter.z + sway);
        this.steerToward(target, this.nestVelocity, ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED,
                ChaseDragonFlight.FOLLOW_MAX_VERTICAL_SPEED, ChaseDragonFlight.FOLLOW_ACCELERATION);
        if (this.fireTurnTicks > 0) {
            this.setYRot(ChaseDragonFlight.turnToward(this.getYRot(), this.fireTurnYaw, 6.0F));
        } else {
            // Facing +X, the chase direction.
            this.setYRot(ChaseDragonFlight.turnToward(this.getYRot(), 90.0F, ChaseDragonFlight.MAX_TURN_DEGREES_PER_TICK));
        }
    }

    private void tickSwoop() {
        Vec3 target = this.trainFront != null
                ? this.trainFront.add(0.0D, 12.0D, 0.0D)
                : this.nestCenter != null ? this.nestCenter.add(0.0D, FOLLOW_HEIGHT, 0.0D) : this.position();
        this.steerToward(target, this.nestVelocity, ChaseDragonFlight.FOLLOW_MAX_HORIZONTAL_SPEED, 1.6D, 0.3D);
        this.faceVelocityOr(90.0F, 6.0F);
        if (this.tickCount >= this.swoopEndTick) {
            this.mode = ChaseDragonHandle.Mode.CHASE_FOLLOW;
        }
    }

    private void tickBreath() {
        if (!this.breathApproached && this.nestCenter != null) {
            // Ride the fleeing nest: feed-forward keeps the dragon above it instead of letting it
            // fall 30-96 blocks behind during the approach.
            Vec3 hover = this.nestCenter.add(0.0D, BREATH_HOVER_HEIGHT, 0.0D);
            this.steerToward(hover, this.nestVelocity, this.breathFollowSpeed(), BREATH_MAX_VERTICAL_SPEED,
                    BREATH_ACCELERATION);
            this.setYRot(ChaseDragonFlight.turnToward(this.getYRot(), -90.0F, 4.0F));
            this.breathTicks++;
            if (this.position().distanceToSqr(hover) < BREATH_ARRIVAL_DISTANCE_SQ || this.breathTicks >= BREATH_APPROACH_TIMEOUT) {
                this.breathApproached = true;
                this.breathTicks = 0;
            }
            return;
        }

        // Hover with the nest, turn back toward the track and breathe for ~3 s. Steering must keep
        // running: the nest keeps fleeing, and the breath segment rides with it (liveBreathFrom/To).
        if (this.nestCenter != null) {
            Vec3 hover = this.nestCenter.add(0.0D, BREATH_HOVER_HEIGHT, 0.0D);
            this.steerToward(hover, this.nestVelocity, this.breathFollowSpeed(), BREATH_MAX_VERTICAL_SPEED,
                    BREATH_ACCELERATION);
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }
        this.setYRot(ChaseDragonFlight.turnToward(this.getYRot(), -90.0F, 6.0F));
        this.breathTicks++;
        if (this.breathTicks % 2 == 0) {
            this.emitBreathParticles();
        }
        if (this.breathTicks >= BREATH_TICKS) {
            ChaseDragons.listener().onBreathFinished(this, this.liveBreathFrom(), this.liveBreathTo());
            this.breathApproached = false;
            this.breathTicks = 0;
            this.mode = ChaseDragonHandle.Mode.CHASE_FOLLOW;
        }
    }

    /** Hover steering speed: at least 3 b/t, always 1.6 b/t above the nest's own speed. */
    private double breathFollowSpeed() {
        return Math.max(BREATH_MIN_HOVER_SPEED, this.nestVelocity.horizontalDistance() + BREATH_SPEED_MARGIN);
    }

    /**
     * Track segment start re-projected onto the current nest position; while the nest anchor is
     * known the breath target follows it instead of staying at the position recorded 3 s ago.
     */
    private Vec3 liveBreathFrom() {
        if (this.nestCenter == null) {
            return this.breathFrom != null ? this.breathFrom : this.position();
        }
        return new Vec3(this.nestCenter.x + this.breathFromOffsetX, this.breathTrackY, 0.0D);
    }

    /** Track segment end, projected like {@link #liveBreathFrom()}. */
    private Vec3 liveBreathTo() {
        if (this.nestCenter == null) {
            return this.breathTo != null ? this.breathTo : this.position();
        }
        return new Vec3(this.nestCenter.x + this.breathToOffsetX, this.breathTrackY, 0.0D);
    }

    private void steerToward(Vec3 target, Vec3 feedForward, double maxHorizontalSpeed, double maxVerticalSpeed, double acceleration) {
        Vec3 desired = ChaseDragonFlight.desiredVelocity(target.subtract(this.position()), feedForward, maxHorizontalSpeed,
                maxVerticalSpeed);
        Vec3 velocity = ChaseDragonFlight.approach(this.getDeltaMovement(), desired, acceleration);
        this.setDeltaMovement(velocity);
        this.move(MoverType.SELF, velocity);
    }

    private void faceVelocityOr(float fallbackYaw, float maxTurn) {
        Vec3 velocity = this.getDeltaMovement();
        float yaw = velocity.horizontalDistanceSqr() > 1.0E-4D ? ChaseDragonFlight.facingYaw(velocity) : fallbackYaw;
        this.setYRot(ChaseDragonFlight.turnToward(this.getYRot(), yaw, maxTurn));
    }

    /** Mouth of the model: about 7.5 blocks ahead of the centre, one block up. */
    public Vec3 mouthPosition() {
        float yawRad = this.getYRot() * (float) (Math.PI / 180.0D);
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
        this.movePart(parts[PART_HEAD], (double) (sinYaw * 6.5F * cosPitch), (double) (headYOffset + sinPitch * 6.5F),
                (double) (-cosYaw * 6.5F * cosPitch));
        this.movePart(parts[PART_NECK], (double) (sinYaw * 5.5F * cosPitch), (double) (headYOffset + sinPitch * 5.5F),
                (double) (-cosYaw * 5.5F * cosPitch));

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
        if (this.mode == ChaseDragonHandle.Mode.CHASE_SWOOP) {
            // Remaining ticks, not the absolute end tick: tickCount is not stable across a reload.
            tag.putInt("SwoopRemaining", Math.max(0, this.swoopEndTick - this.tickCount));
        }
        if (this.mode == ChaseDragonHandle.Mode.CHASE_BREATH) {
            tag.putBoolean("BreathApproached", this.breathApproached);
            tag.putInt("BreathTicks", this.breathTicks);
            tag.putDouble("BreathFromOffsetX", this.breathFromOffsetX);
            tag.putDouble("BreathToOffsetX", this.breathToOffsetX);
            tag.putDouble("BreathTrackY", this.breathTrackY);
            if (this.breathFrom != null) {
                tag.putDouble("BreathFromX", this.breathFrom.x);
                tag.putDouble("BreathFromY", this.breathFrom.y);
                tag.putDouble("BreathFromZ", this.breathFrom.z);
            }
            if (this.breathTo != null) {
                tag.putDouble("BreathToX", this.breathTo.x);
                tag.putDouble("BreathToY", this.breathTo.y);
                tag.putDouble("BreathToZ", this.breathTo.z);
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
        }
        this.breathApproached = false;
        this.breathTicks = 0;
        if (tag.contains("ChaseMode")) {
            ChaseDragonHandle.Mode saved;
            try {
                saved = ChaseDragonHandle.Mode.valueOf(tag.getString("ChaseMode"));
            } catch (IllegalArgumentException exception) {
                saved = ChaseDragonHandle.Mode.CHASE_FOLLOW;
            }
            // Anchors are re-sent by the director every tick; follow restarts cleanly. Swoop and
            // breath keep their remaining ticks/segment so a reload mid-animation still finishes
            // and the breath zone is laid down afterwards.
            switch (saved) {
                case DYING, PROLOGUE_ORBIT, PROLOGUE_DEPART -> this.mode = saved;
                case CHASE_SWOOP -> {
                    this.mode = ChaseDragonHandle.Mode.CHASE_SWOOP;
                    this.swoopEndTick = this.tickCount + Math.max(1, tag.getInt("SwoopRemaining"));
                }
                case CHASE_BREATH -> {
                    this.mode = ChaseDragonHandle.Mode.CHASE_BREATH;
                    this.breathFromOffsetX = tag.getDouble("BreathFromOffsetX");
                    this.breathToOffsetX = tag.getDouble("BreathToOffsetX");
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
                default -> this.mode = ChaseDragonHandle.Mode.CHASE_FOLLOW;
            }
        }
    }
}
