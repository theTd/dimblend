package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;

/** Beds provide rest, without advancing time, in the overworld and rotating world. */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class BedRestRules {
    public static final int HEAL_INTERVAL_TICKS = 100;
    public static final float HEAL_AMOUNT = 1.0F;
    public static final int WOUNDED_PARTICLE_INTERVAL_TICKS = 20;

    public static boolean applies(Level level) {
        return level.dimension().equals(Level.OVERWORLD)
                || (Config.isLoaded() && RotatingDimension.is(level));
    }

    public static boolean isResting(Player player) {
        return !player.level().isClientSide && player.isAlive() && player.isSleeping()
                && applies(player.level());
    }

    public static void showRestHearts(Player player) {
        if (!(player.level() instanceof ServerLevel level) || !isResting(player)) return;
        var center = SableWorldPosition.project(level, player.position()).add(0.0, 0.6, 0.0);
        int count = player.getHealth() < player.getMaxHealth() ? 3 : 1;
        level.sendParticles(ParticleTypes.HEART, center.x, center.y, center.z,
                count, 0.35, 0.15, 0.35, 0.0);
    }

    @SubscribeEvent
    public static void onSleepAttempt(CanPlayerSleepEvent event) {
        if (!applies(event.getLevel())) return;
        var problem = event.getProblem();
        if (problem == Player.BedSleepingProblem.NOT_POSSIBLE_NOW
                || problem == Player.BedSleepingProblem.NOT_SAFE) {
            event.setProblem(null);
        }
    }

    @SubscribeEvent
    public static void onContinueSleeping(CanContinueSleepingEvent event) {
        if (event.getEntity() instanceof Player player && applies(player.level())
                && event.getProblem() == Player.BedSleepingProblem.NOT_POSSIBLE_NOW) {
            event.setContinueSleeping(true);
        }
    }

    private BedRestRules() { }
}
