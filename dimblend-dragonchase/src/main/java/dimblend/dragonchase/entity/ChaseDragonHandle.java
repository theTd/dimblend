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
        /** §3.4: follow the nest anchor (x aligned, sway z ±32, y ±16 around nest + 32). */
        CHASE_FOLLOW,
        /** §3.4: swoop over the train for the requested ticks, then return to CHASE_FOLLOW. */
        CHASE_SWOOP,
        /** §3.4: hover at the lowest point above the nest, turn back, breathe onto the track. */
        CHASE_BREATH,
        /** §3.6: vanilla death animation and sound only, at the kill position; no drops, no XP. */
        DYING
    }

    UUID uuid();

    Mode mode();

    boolean isAliveAndLoaded();

    /** Current health (already reduced when {@link ChaseDragonListener#onDamageTaken} fires). Inherited from LivingEntity. */
    float getHealth();

    /** Max health attribute value. Inherited from LivingEntity. */
    float getMaxHealth();

    /** Prologue: the point to circle (centroid of the farthest force-loaded sub-level) and the track Y. */
    void setOrbitCenter(Vec3 center, double trackY);

    /** Prologue: start the departure (§3.2). Idempotent. */
    void depart();

    /** Chase: the nest's world centre and the leading train's front, updated every tick by the director. */
    void setChaseAnchors(Vec3 nestCenter, Vec3 trainFront);

    /**
     * Chase: turn back and fire one dragon fireball that lands exactly on {@code target}
     * (§3.4: 12 blocks behind the nest on the track). Impact plays an explosion sound only:
     * no damage, no block damage, no lingering cloud. Fires {@link ChaseDragonListener#onFireballImpact}.
     *
     * @return false when the dragon is busy (dying, breathing) and did not fire
     */
    boolean fireFireballAt(Vec3 target);

    /** Chase: swoop over the train for {@code ticks}, then return (decision dragon-position). */
    void beginSwoop(int ticks);

    /**
     * Chase: fly to the lowest point above the nest, turn back and breathe onto the track segment
     * from {@code trackFrom} to {@code trackTo} (§3.4). Fires {@link ChaseDragonListener#onBreathFinished}
     * when the breath animation ends.
     */
    void breatheOnto(Vec3 trackFrom, Vec3 trackTo);

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
