package dimblend.dragonchase.carriage;

import com.simibubi.create.AllSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The particles and sounds of the car rules: red dust, black smoke, white smoke, the dissolve burst,
 * the fall-away explosion and the nest's portal hint.
 *
 * <p><b>The dissolve burst copies the reward wagon's, deliberately.</b> A vanilla mob's death plays
 * {@code ParticleTypes.POOF} twenty times ({@code LivingEntity.java:1841,1851-1858}); the requirement
 * asks for the same burst spread over a radius of five at the bogey's y-1, and for the same sound —
 * Create's {@code WRENCH_REMOVE}, which is itself the "wrench_remove" group of
 * {@code ITEM_PICKUP} + {@code NETHERITE_BLOCK_HIT}, so one event plays both halves.
 *
 * <p><b>Uniform inside the ball.</b> A direction times a uniform radius clumps at the centre;
 * scaling by the cube root of the draw makes every unit of volume equally likely, so "radius 5"
 * looks like a sphere rather than a puff.
 */
public final class CarEffects {

    /** Radius of the dissolve burst, from the requirement. */
    public static final double DISSOLVE_RADIUS = 5.0;
    /** The vanilla mob-death particle count. */
    public static final int DISSOLVE_PARTICLES = 20;
    /** How far from the impact a player still sees the burst, in blocks. */
    public static final double IMPACT_RADIUS = 512.0;

    private CarEffects() {
    }

    /** The reward-wagon dissolve feedback: twenty poofs in a radius-five ball, plus the sound. */
    public static void dissolveBurst(ServerLevel level, Vec3 centre) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < DISSOLVE_PARTICLES; i++) {
            double[] at = sampleInBall(random, DISSOLVE_RADIUS);
            level.sendParticles(ParticleTypes.POOF,
                    centre.x + at[0], centre.y + at[1], centre.z + at[2], 1, 0.0, 0.0, 0.0, 0.0);
        }
        AllSoundEvents.WRENCH_REMOVE.playOnServer(level, BlockPos.containing(centre), 1.0F, 1.0F);
    }

    /** Red dust drifting over the whole car: the brake car's running look. */
    public static void redDust(ServerLevel level, AABB box, RandomSource random) {
        for (int i = 0; i < 2; i++) {
            Vec3 at = inside(box, random);
            level.sendParticles(DustParticleOptions.REDSTONE, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** Black smoke over the whole car, for the eight seconds before a brake car falls away. */
    public static void blackSmoke(ServerLevel level, AABB box, RandomSource random) {
        for (int i = 0; i < 6; i++) {
            Vec3 at = inside(box, random);
            level.sendParticles(i % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    at.x, at.y, at.z, 1, 0.0, 0.02, 0.0, 0.01);
        }
    }

    /** White smoke: the brake car letting go of its couplers. */
    public static void whiteSmoke(ServerLevel level, AABB box, RandomSource random) {
        for (int i = 0; i < 12; i++) {
            Vec3 at = inside(box, random);
            level.sendParticles(ParticleTypes.WHITE_SMOKE, at.x, at.y, at.z, 1, 0.0, 0.06, 0.0, 0.02);
        }
    }

    /**
     * The fireball impact and the car spawn burst: vanilla's big explosion plus a cloud of dragon
     * breath and poof, sent per player with {@code overrideLimiter}.
     *
     * <p><b>Why per player.</b> {@code ServerLevel#sendParticles} without a player culls at 32 blocks
     * ({@code ServerLevel.java:1233-1245}: {@code closerToCenterThan(..., overrideLimiter ? 512 : 32)}),
     * and the chase gap between the dragon and the players is 128-160+ blocks — a level-wide call is
     * invisible. {@code ChaseDragon#emitBreathParticles} and {@code ChaseBreathZones#emit} already use
     * the same overload. The 512-block guard matches the sender's own limit.
     *
     * <p><b>An effect, never an explosion.</b> No {@code level.explode} of any interaction: requirements
     * §3.4 asks for the sound and particles with no damage, no knockback and no dragon-breath cloud.
     */
    public static void impactBurst(ServerLevel level, Vec3 centre) {
        double radiusSq = IMPACT_RADIUS * IMPACT_RADIUS;
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - centre.x;
            double dz = player.getZ() - centre.z;
            if (dx * dx + dz * dz > radiusSq) {
                continue;
            }
            level.sendParticles(player, ParticleTypes.EXPLOSION_EMITTER, true,
                    centre.x, centre.y, centre.z, 1, 0.0, 0.0, 0.0, 0.0);
            level.sendParticles(player, ParticleTypes.DRAGON_BREATH, true,
                    centre.x, centre.y, centre.z, 28, 2.0, 1.5, 2.0, 0.03);
            level.sendParticles(player, ParticleTypes.POOF, true,
                    centre.x, centre.y, centre.z, 16, 1.5, 1.0, 1.5, 0.03);
        }
    }

    /** The fall-away explosion, which is an effect and nothing else: no damage, no block damage
     * (decision fall-style). {@code EXPLOSION_EMITTER} is the vanilla "big" explosion visual and
     * {@code GENERIC_EXPLODE} its sound.
     */
    public static void explosion(ServerLevel level, Vec3 centre) {
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 1,
                0.0, 0.0, 0.0, 0.0);
        level.playSound(null, centre.x, centre.y, centre.z,
                SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0F, 1.0F);
    }

    /** The continuous end-portal hint under the nest's shulker box (requirements.md §3.6). */
    public static void portalHint(ServerLevel level, Vec3 centre, RandomSource random) {
        for (int i = 0; i < 3; i++) {
            level.sendParticles(ParticleTypes.PORTAL,
                    centre.x + (random.nextDouble() - 0.5) * 1.2,
                    centre.y + (random.nextDouble() - 0.5) * 0.4,
                    centre.z + (random.nextDouble() - 0.5) * 1.2,
                    1, 0.0, 0.0, 0.0, 0.05);
        }
    }

    /** One position uniformly inside the box. */
    private static Vec3 inside(AABB box, RandomSource random) {
        return new Vec3(
                box.minX + random.nextDouble() * (box.maxX - box.minX),
                box.minY + random.nextDouble() * (box.maxY - box.minY),
                box.minZ + random.nextDouble() * (box.maxZ - box.minZ));
    }

    /** One offset uniformly inside a sphere of {@code radius}. */
    private static double[] sampleInBall(RandomSource random, double radius) {
        double azimuth = random.nextDouble() * Math.PI * 2.0;
        double cosine = random.nextDouble() * 2.0 - 1.0;
        double sine = Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        double r = radius * Math.cbrt(random.nextDouble());
        return new double[] {
                r * sine * Math.cos(azimuth),
                r * cosine,
                r * sine * Math.sin(azimuth)};
    }
}
