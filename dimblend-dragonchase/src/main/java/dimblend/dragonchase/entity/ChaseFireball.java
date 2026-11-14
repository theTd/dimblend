package dimblend.dragonchase.entity;

import dimblend.dragonchase.DragonChaseRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The chase dragon's breath shot (requirements.md §3.4).
 *
 * <p>Reuses vanilla {@code DragonFireball} so the vanilla fireball renderer and texture apply
 * (registered by {@code DragonChaseClient}), but none of its behaviour: the projectile flies a
 * straight line from the mouth and lands <em>exactly</em> on the target point, where it only plays
 * an explosion sound, reports {@link ChaseDragonListener#onFireballImpact} and discards itself --
 * no damage, no block damage, no lingering area-effect cloud
 * (vanilla's cloud is {@code DragonFireball.onHit},
 * {@code E:\misc\mcsrc-1.21.1\net\minecraft\world\entity\projectile\DragonFireball.java:31-59}).
 */
public class ChaseFireball extends DragonFireball {
    /** Flight time of one shot in ticks; the speed follows from the distance. */
    public static final int TRAVEL_TICKS = 20;

    private Vec3 launchPos = Vec3.ZERO;
    private Vec3 targetPos = Vec3.ZERO;
    private int travelTicks = TRAVEL_TICKS;
    private int elapsedTicks;

    public ChaseFireball(EntityType<? extends ChaseFireball> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** Aims the shot; must be called on the server thread before the entity is added. */
    public void launch(Vec3 from, Vec3 target, int travelTicks) {
        this.launchPos = from;
        this.targetPos = target;
        this.travelTicks = Math.max(1, travelTicks);
        this.elapsedTicks = 0;
        this.setPos(from.x, from.y, from.z);
    }

    @Override
    public void tick() {
        this.baseTick();
        if (this.level().isClientSide) {
            this.level().addParticle(ParticleTypes.DRAGON_BREATH, this.getX(), this.getY(), this.getZ(), 0.0D, 0.0D, 0.0D);
            return;
        }
        if (this.isRemoved()) {
            return;
        }
        this.elapsedTicks++;
        Vec3 next = FireballPath.positionAt(this.launchPos, this.targetPos, this.elapsedTicks, this.travelTicks);
        this.setPos(next.x, next.y, next.z);
        if (this.level() instanceof ServerLevel serverLevel && (this.elapsedTicks == 1 || this.elapsedTicks % 10 == 0)) {
            ChaseEntityTickets.keepTicking(serverLevel, this);
        }
        if (FireballPath.arrived(this.elapsedTicks, this.travelTicks)) {
            this.impact();
        }
    }

    private void impact() {
        // volume 16 = 16 * 16 = 256 blocks of server broadcast and OpenAL range: the players sit
        // 128-160+ blocks behind the impact point, where vanilla's volume 4 (64 blocks) is silent.
        this.level()
                .playSound(null, this.targetPos.x, this.targetPos.y, this.targetPos.z, SoundEvents.DRAGON_FIREBALL_EXPLODE,
                        SoundSource.HOSTILE, 16.0F, 1.0F);
        if (this.getOwner() instanceof ChaseDragonHandle dragon) {
            ChaseDragons.listener().onFireballImpact(dragon, this.targetPos);
        }
        this.discard();
    }

    /** Never hits anything: the shot is a scripted fly-through (no damage, no cloud). */
    @Override
    protected void onHit(HitResult result) {
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }

    /**
     * Persists the scripted trajectory: without it a shot saved with the chunk would resume at the
     * world origin after a reload instead of finishing its line to the target.
     *
     * <p>{@code public} (vanilla's override is public in
     * {@code AbstractHurtingProjectile.java:143}) so the GameTest can round-trip it directly.
     */
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("LaunchX", this.launchPos.x);
        tag.putDouble("LaunchY", this.launchPos.y);
        tag.putDouble("LaunchZ", this.launchPos.z);
        tag.putDouble("TargetX", this.targetPos.x);
        tag.putDouble("TargetY", this.targetPos.y);
        tag.putDouble("TargetZ", this.targetPos.z);
        tag.putInt("TravelTicks", this.travelTicks);
        tag.putInt("ElapsedTicks", this.elapsedTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("TargetX")) {
            this.launchPos = new Vec3(tag.getDouble("LaunchX"), tag.getDouble("LaunchY"), tag.getDouble("LaunchZ"));
            this.targetPos = new Vec3(tag.getDouble("TargetX"), tag.getDouble("TargetY"), tag.getDouble("TargetZ"));
            this.travelTicks = Math.max(1, tag.getInt("TravelTicks"));
            this.elapsedTicks = Math.max(0, tag.getInt("ElapsedTicks"));
        } else {
            // Trajectory written by a build without persistence: finish harmlessly in place rather
            // than fly to the world origin.
            this.launchPos = this.position();
            this.targetPos = this.position();
            this.travelTicks = 1;
            this.elapsedTicks = 1;
        }
    }

    /** Convenience for callers that already built a fireball for {@code dragon}. */
    static ChaseFireball spawn(Level level, ChaseDragon dragon, Vec3 from, Vec3 target, int travelTicks) {
        ChaseFireball fireball = new ChaseFireball(DragonChaseRegistries.DRAGON_FIREBALL.get(), level);
        fireball.setOwner(dragon);
        fireball.launch(from, target, travelTicks);
        return fireball;
    }
}
