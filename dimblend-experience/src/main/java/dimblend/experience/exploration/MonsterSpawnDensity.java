package dimblend.experience.exploration;

import java.util.Set;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;

/**
 * A12 rotating-dimension monster density decision (pure; the random value is injected).
 *
 * <p>One percentage {@code p} (the {@code rotatingMonsterCapMultiplier} config value,
 * shown as 0-100 in the tuning panel) scales every covered monster spawn source in the
 * rotating dimension. A covered spawn is cancelled with probability {@code 1 - p}.
 *
 * <p>Natural spawns apply {@code p} in two places on purpose. The global cap is
 * scaled by {@code p} in {@code NaturalSpawnerCapMixin}, and
 * every natural mob that reaches {@code finalizeSpawn} is kept with probability
 * {@code p} here. The two are not multiplied: the cap is a population ceiling while
 * the roll is a survival probability, so at equilibrium the population is
 * {@code min(p * C, p * R) = p * min(C, R)} (approximately {@code p} of normal), where
 * {@code C} is the vanilla cap-bound population and {@code R} the rate-bound one.
 *
 * <p>Never suppressed: non-MONSTER categories, bosses (checked by the caller), spawn
 * types outside {@link #isCovered(MobSpawnType)} (player-caused and mob-conversion
 * spawns), and any level that is not the rotating dimension.
 */
public final class MonsterSpawnDensity {

    /**
     * Spawn sources covered by the density percentage. NATURAL includes the structure
     * overrides applied inside the natural spawner. Player-caused types (SPAWN_EGG,
     * COMMAND, BUCKET, DISPENSER, BREEDING, MOB_SUMMONED, CONVERSION, TRIGGERED, EVENT)
     * are intentionally absent. JOCKEY is listed for completeness; in vanilla it only
     * finalizes a chicken rider (a CREATURE), so it never suppresses a MONSTER.
     */
    private static final Set<MobSpawnType> COVERED_SPAWN_TYPES = Set.of(
            MobSpawnType.NATURAL,
            MobSpawnType.SPAWNER,
            MobSpawnType.TRIAL_SPAWNER,
            MobSpawnType.STRUCTURE,
            MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.PATROL,
            MobSpawnType.REINFORCEMENT,
            MobSpawnType.JOCKEY);

    /**
     * The single decision used by the event guard. {@code roll} must come from
     * {@link net.minecraft.util.RandomSource#nextDouble()} (or an injected value in
     * tests); a roll below {@code density} keeps the spawn.
     */
    public static boolean shouldCancel(MobCategory category, MobSpawnType spawnType, boolean boss,
            boolean rotatingDimension, double density, double roll) {
        if (!rotatingDimension || density >= 1.0D) {
            return false;
        }
        if (category != MobCategory.MONSTER || boss) {
            return false;
        }
        if (!isCovered(spawnType)) {
            return false;
        }
        // At density 0 every roll (including 0) cancels; at 1 the early return above
        // keeps vanilla behaviour byte-for-byte.
        return roll >= density;
    }

    public static boolean isCovered(MobSpawnType spawnType) {
        return spawnType != null && COVERED_SPAWN_TYPES.contains(spawnType);
    }

    private MonsterSpawnDensity() {
    }
}
