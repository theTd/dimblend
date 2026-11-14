package dimblend.dragonchase.chase;

import dimblend.dragonchase.api.DragonChaseTuning;
import dimblend.dragonchase.entity.ChaseDragonHandle;
import dimblend.dragonchase.entity.ChaseDragonListener;
import dimblend.dragonchase.structure.CarKind;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The dragon's callbacks, turned into chase state. Installed by {@link ChaseBootstrap}; called by
 * W3's dragon on the server thread.
 *
 * <p>The health thresholds read the dragon's own live {@code getHealth() / getMaxHealth()}: the
 * per-second cap and crystal healing are applied by the entity, so the director keeps no health
 * bookkeeping of its own. Damage is summed for one purpose only — the §3.4 fireball accumulator.
 */
final class ChaseListener implements ChaseDragonListener {
    @Override
    public void onDamageTaken(ChaseDragonHandle dragon, float appliedDamage) {
        ServerLevel level = ChaseDirector.rotatingLevel();
        if (level == null) {
            return;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        if (dragon.uuid().equals(data.prologueDragonId)) {
            // §3.2: any damage ends the prologue.
            if (!data.prologueDamageTaken) {
                data.prologueDamageTaken = true;
                data.setDirty();
            }
            return;
        }
        if (data.phase != DragonChaseSavedData.Phase.ACTIVE || data.dragonId == null
                || !dragon.uuid().equals(data.dragonId) || appliedDamage <= 0.0F) {
            return;
        }

        long now = level.getGameTime();
        data.fireballAccumulator = ChaseMath.accumulateDamage(data.fireballAccumulator, appliedDamage, now,
                data.fireballCooldownUntil);

        // §3.4: threshold reached -> reset, fire at the track 12 blocks behind the nest, 8 s cooldown, swoop.
        if (data.nestId != null
                && ChaseMath.fireballReady(data.fireballAccumulator, DragonChaseTuning.fireballDamageThreshold())) {
            Vec3 target = new Vec3(data.nestCenterX - ChaseMath.FIREBALL_BACK, ChaseMath.FIREBALL_Y, 0.0D);
            if (dragon.fireFireballAt(target)) {
                data.fireballAccumulator = 0.0D;
                data.fireballCooldownUntil = now + ChaseMath.FIREBALL_COOLDOWN_TICKS;
                dragon.beginSwoop(200);
            }
        }

        // §3.4: each health threshold, once, sends the dragon to breathe on the track behind the nest.
        if (data.nestId != null) {
            // Real health from the entity (the cap and crystal healing are applied there).
            double fraction = dragon.getMaxHealth() > 0.0F ? dragon.getHealth() / dragon.getMaxHealth() : 1.0D;
            int index = ChaseMath.firstUnfiredThreshold(fraction, data.breathFired);
            if (index >= 0) {
                data.breathFired[index] = true;
                ChaseDirector.breatheBehindNest(dragon, data.nestCenterX);
            }
        }
        data.setDirty();
    }

    @Override
    public void onFireballImpact(ChaseDragonHandle dragon, Vec3 impact) {
        ServerLevel level = ChaseDirector.rotatingLevel();
        if (level == null) {
            return;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        // §3.6: after the kill there is no keep-away and no forced End; the fireball branch is ACTIVE-only.
        if (data.phase != DragonChaseSavedData.Phase.ACTIVE) {
            return;
        }

        List<ChaseTrackScan.Car> corridor = ChaseTrackScan.corridor(level, ChaseTrackScan.forceLoaded(level));
        ChaseTrackScan.Car leader = ChaseDirector.leaderBehind(corridor, data.nestId, data.nestCenterX);
        double leaderSpeed = leader == null ? 0.0D : leader.vx();

        // §3.4 decision car-spawn-speed: the dropped car starts about 5 m/s slower, never backwards.
        data.pendingCarKind = level.random.nextBoolean() ? CarKind.BRAKE : CarKind.CRYSTAL;
        data.pendingCarX = Mth.floor(impact.x);
        data.pendingCarVx = Math.max(0.0D, leaderSpeed - ChaseMath.DROPPED_CAR_SPEED_LOSS);
        data.pendingCarDeadline = level.getGameTime() + ChaseMath.DROPPED_CAR_LOAD_TIMEOUT_TICKS;
        data.pendingCarRetryAt = 0L;
        data.setDirty();
    }

    @Override
    public void onBreathFinished(ChaseDragonHandle dragon, Vec3 trackFrom, Vec3 trackTo) {
        ServerLevel level = ChaseDirector.rotatingLevel();
        if (level == null) {
            return;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        if (data.phase != DragonChaseSavedData.Phase.ACTIVE) {
            return;
        }
        ChaseBreathZones.place(data, trackFrom.x, trackTo.x);
    }

    @Override
    public void onKilled(ChaseDragonHandle dragon) {
        ServerLevel level = ChaseDirector.rotatingLevel();
        if (level == null) {
            return;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        if (data.dragonId == null || !dragon.uuid().equals(data.dragonId)) {
            return;
        }
        ChaseDirector.killChase(level, data);
    }

    @Override
    public void onDeparted(ChaseDragonHandle dragon) {
        ServerLevel level = ChaseDirector.rotatingLevel();
        if (level == null) {
            return;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        data.prologueDeparting = false;
        data.prologueDragonId = null;
        // §3.2 decision end-advancement: "Free the End" to players near the orbited car; never end/root.
        ChaseAdvancements.grantWithin(level, data.orbitX, data.orbitZ, ChaseAdvancements.KILL_DRAGON,
                ChaseAdvancements.KILL_DRAGON_CRITERION);
        data.setDirty();
    }
}
