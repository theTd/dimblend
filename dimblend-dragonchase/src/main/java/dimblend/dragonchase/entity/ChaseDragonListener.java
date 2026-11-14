package dimblend.dragonchase.entity;

import net.minecraft.world.phys.Vec3;

/**
 * CONTRACT (Phase 0). Callbacks from the dragon to the director. Installed by W5 through
 * {@link ChaseDragons#setListener}; invoked by W3 on the server thread.
 */
public interface ChaseDragonListener {
    /**
     * Health actually removed by one hit, after the per-second damage cap (§3.4) has discarded
     * the excess. Called for prologue dragons too (any damage triggers departure, §3.2).
     */
    void onDamageTaken(ChaseDragonHandle dragon, float appliedDamage);

    /** The fireball reached its target point. */
    void onFireballImpact(ChaseDragonHandle dragon, Vec3 impact);

    /** The breath animation finished; the director now lays the lingering breath zone on the track. */
    void onBreathFinished(ChaseDragonHandle dragon, Vec3 trackFrom, Vec3 trackTo);

    /** Health reached zero; the death animation is starting (§3.6). */
    void onKilled(ChaseDragonHandle dragon);

    /** Prologue dragon reached y = 512 and discarded itself. */
    void onDeparted(ChaseDragonHandle dragon);

    ChaseDragonListener NONE = new ChaseDragonListener() {
        @Override
        public void onDamageTaken(ChaseDragonHandle dragon, float appliedDamage) {
        }

        @Override
        public void onFireballImpact(ChaseDragonHandle dragon, Vec3 impact) {
        }

        @Override
        public void onBreathFinished(ChaseDragonHandle dragon, Vec3 trackFrom, Vec3 trackTo) {
        }

        @Override
        public void onKilled(ChaseDragonHandle dragon) {
        }

        @Override
        public void onDeparted(ChaseDragonHandle dragon) {
        }
    };
}
