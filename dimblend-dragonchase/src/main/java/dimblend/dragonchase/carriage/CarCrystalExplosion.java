package dimblend.dragonchase.carriage;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Turns the explosion of a crystal car's end crystal into knockback only: no damage to anything, no
 * block damage, the vanilla 6.0F shove kept, and a single vanilla explosion packet (sound +
 * particles) sent to clients.
 *
 * <p><b>Why intercept at all.</b> Vanilla {@code EndCrystal#hurt} removes the crystal and then calls
 * {@code Level#explode(..., 6.0F, false, BLOCK)} ({@code EndCrystal.java:97}). That blast hurts the
 * player who hit it, damages every entity in a 12-block box and breaks blocks. On a crystal car that
 * means the crystals sitting next to the one being hit are damaged too (an explosion-damaged crystal
 * is removed without exploding again, {@code EndCrystal.java:95-98}), so one hit empties the whole
 * car. The requirement is the opposite: <i>the crystals only knock back, they do not hurt, and the
 * player has to break them one by one</i> — the melee hit stays dangerous because the shove can throw
 * the player off the car, not because it costs hearts.
 *
 * <p><b>How.</b> Cancel {@link ExplosionEvent.Start} for a car crystal so the original explosion
 * skips {@code explode()} (leaving {@code toBlow} empty and dealing zero damage). Then run a local
 * {@link Explosion} constructed with {@link #KNOCKBACK_ONLY} and {@code BlockInteraction.KEEP}: its
 * {@code explode()} applies the full vanilla 6.0F shove to server entities, honours NeoForge's
 * {@code ExplosionEvent.Detonate} / knockback hooks, and populates {@code hitPlayers} without
 * hurting anything or breaking blocks. Finally copy {@code hitPlayers} into the original
 * {@code event.getExplosion().getHitPlayers()}: NeoForge-patched {@code ServerLevel#explode}
 * ({@code ServerLevel.java:1107-1135}) still sends one {@code ClientboundExplodePacket} for the
 * cancelled explosion, so clients receive the exact vanilla knockback vector together with a single
 * vanilla sound and particle burst (no doubled boom, no re-entrancy into {@code ExplosionEvent.Start}).
 *
 * <p><b>Which crystals.</b> Only crystals this mod spawned on a crystal car: the persisted
 * {@link #CRYSTAL_TAG} entity tag, or a slot {@link CarRegistry#ownsCrystal} still names (crystals
 * from saves written before the tag existed). A crystal a player placed beside a car keeps its
 * ordinary explosion.
 */
public final class CarCrystalExplosion {

    /**
     * Persisted entity tag on every end crystal a crystal car spawns ({@code Entity#addTag} survives
     * a save through the entity's {@code Tags} list).
     */
    public static final String CRYSTAL_TAG = "dimblend_dragonchase.car_crystal";

    /**
     * The blast a car crystal gets instead of the vanilla one: nothing is damaged, nothing is broken,
     * the shove is the vanilla amount.
     *
     * <p>{@code shouldDamageEntity} returning {@code false} is also what stops the chain: an
     * explosion-damaged crystal is removed without exploding again, so suppressing the damage is
     * exactly "the neighbouring crystals survive" ({@code Explosion.java:286-288}).
     */
    public static final ExplosionDamageCalculator KNOCKBACK_ONLY = new ExplosionDamageCalculator() {
        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos,
                BlockState state, float power) {
            return false;
        }
    };

    /** The event handler {@link CarriageBootstrap} registers. */
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getExplosion().getDirectSourceEntity() instanceof EndCrystal crystal)) {
            return;
        }
        if (!isCarCrystal(level, crystal)) {
            return;
        }
        event.setCanceled(true);
        Explosion original = event.getExplosion();
        Vec3 center = original.center();
        float radius = original.radius();
        Explosion knockbackOnly = new Explosion(
                level,
                crystal,
                null,
                KNOCKBACK_ONLY,
                center.x,
                center.y,
                center.z,
                radius,
                false,
                Explosion.BlockInteraction.KEEP,
                ParticleTypes.EXPLOSION,
                ParticleTypes.EXPLOSION_EMITTER,
                SoundEvents.GENERIC_EXPLODE);
        knockbackOnly.explode();
        original.getHitPlayers().putAll(knockbackOnly.getHitPlayers());
    }

    /**
     * True when {@code crystal} is one of this world's car crystals: tagged at spawn, or named by a
     * registered car's record (a crystal from a save written before the tag existed).
     */
    public static boolean isCarCrystal(ServerLevel level, EndCrystal crystal) {
        return hasCarTag(crystal.getTags()) || CarRegistry.get(level).ownsCrystal(crystal.getUUID());
    }

    /** The tag half of {@link #isCarCrystal}, pure so the unit test can pin it. */
    public static boolean hasCarTag(Set<String> tags) {
        return tags.contains(CRYSTAL_TAG);
    }

    private CarCrystalExplosion() {
    }
}
