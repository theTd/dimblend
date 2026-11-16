package dimblend.dragonchase.entity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.Vec3;

/**
 * CONTRACT (Phase 0, owned by the orchestrator; implemented by W3's dragon entity).
 *
 * <p>What the director (W5) may ask of a live dragon. The dragon decides <em>how</em> it flies and
 * animates; the director decides <em>when</em>. All methods are server-thread only and must be
 * cheap to call every tick (setters store targets; motion happens in the entity's own tick).
 *
 * <p>Requirement references are to dimblend-dragonchase/docs/requirements.md.
 */
public interface ChaseDragonHandle {
    enum Mode {
        /** §3.2: circle {@link #setOrbitCenter} at the prologue altitude, idle calls, no boss bar. */
        PROLOGUE_ORBIT,
        /** §3.2: fly forward to track + 32, then along the track climbing 45° to y = 512, then discard itself and fire {@link ChaseDragonListener#onDeparted}. */
        PROLOGUE_DEPART,
        /** §3.4: hold above the nest (x aligned, sway z ±32, y ±16 around nest + 32). */
        CHASE_FOLLOW,
        /** §3.4: no turn-around; pull quickly back past the nest to the train-front anchor and fire one fireball onto the predicted track impact point, then CHASE_CATCH_UP. */
        CHASE_FIREBALL,
        /** §3.4: no turn-around; retreat at low altitude to 50% of the nest-train gap and spray breath onto the track, then CHASE_CATCH_UP. */
        CHASE_BREATH,
        /** §3.4: after a fireball or breath, gradually close back up on the nest, then CHASE_FOLLOW. */
        CHASE_CATCH_UP,
        /** §3.6: vanilla death animation and sound only, at the kill position; no drops, no XP. */
        DYING
    }

    UUID uuid();

    Mode mode();

    boolean isAliveAndLoaded();

    /** True when the dragon can start a fireball or breath manoeuvre (i.e. in {@link Mode#CHASE_FOLLOW} or {@link Mode#CHASE_CATCH_UP}, alive and loaded). */
    default boolean canStartChaseAction() {
        Mode current = mode();
        return isAliveAndLoaded() && (current == Mode.CHASE_FOLLOW || current == Mode.CHASE_CATCH_UP);
    }

    /** Current health (already reduced when {@link ChaseDragonListener#onDamageTaken} fires). Inherited from LivingEntity. */
    float getHealth();

    /** Max health attribute value. Inherited from LivingEntity. */
    float getMaxHealth();

    /** Prologue: the point to circle (centroid of the farthest force-loaded sub-level) and the track Y. */
    void setOrbitCenter(Vec3 center, double trackY);

    /** Prologue: start the departure (§3.2). Idempotent. */
    void depart();

    /**
     * Chase: the nest's world centre and the leading train's front reference point, updated every
     * tick by the director. The dragon keeps its distance from the nest and uses the train front as
     * the retreat target when it fires a fireball or breathes.
     */
    void setChaseAnchors(Vec3 nestCenter, Vec3 trainFront);

    /**
     * Chase: without turning around, pull quickly back past the nest to the train-front anchor and
     * fire one dragon fireball that lands exactly on {@code target} (§3.4: the predicted track impact
     * point ahead of the train), then gradually catch back up to the nest (CHASE_CATCH_UP).
     * Impact plays an explosion sound only: no damage, no block damage, no lingering cloud. Fires
     * {@link ChaseDragonListener#onFireballImpact}.
     *
     * @return false when the dragon is busy (dying, already retreating/shooting/breathing, or prologue) and did not fire
     */
    boolean fireFireballAt(Vec3 target);

    /**
     * Chase: without turning around, retreat at low altitude to 50% of the nest-train gap and spray
     * breath forward onto the track segment from {@code trackFrom} to {@code trackTo} (§3.4), then
     * gradually catch back up to the nest (CHASE_CATCH_UP). Fires
     * {@link ChaseDragonListener#onBreathFinished} when the breath spray finishes.
     *
     * @return false when the dragon is busy (dying, already retreating/shooting/breathing, or prologue) and did not start
     */
    boolean breatheOnto(Vec3 trackFrom, Vec3 trackTo);

    /** Boss bar title; {@code null} hides the bar (prologue). */
    void setBossBarName(Component name);

    /** Exactly these players see the boss bar from now on. */
    void setBossBarViewers(Collection<ServerPlayer> players);

    /** Crystals that currently heal this dragon: 1 health per second each, no range limit, beams drawn to each. */
    void setHealingCrystals(List<EndCrystal> crystals);

    /**
     * Every alive healing crystal of the chase, including those whose chunk is not loaded
     * (requirements.md §3.4 decision crystal-heal: no range limit, 1-4 health per second).
     * Healing uses this count; {@link #setHealingCrystals} only supplies the beam targets.
     */
    void setHealingCrystalCount(int count);

    /** Remove without a death animation (nest deleted, chase failed). */
    void discardSilently();
}
