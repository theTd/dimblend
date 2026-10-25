package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import org.junit.jupiter.api.Test;

class MonsterSpawnDensityTest {

    private static final Set<MobSpawnType> COVERED = EnumSet.of(
            MobSpawnType.NATURAL,
            MobSpawnType.SPAWNER,
            MobSpawnType.TRIAL_SPAWNER,
            MobSpawnType.STRUCTURE,
            MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.PATROL,
            MobSpawnType.REINFORCEMENT,
            MobSpawnType.JOCKEY);

    private static final Set<MobSpawnType> PLAYER_CAUSED = EnumSet.of(
            MobSpawnType.SPAWN_EGG,
            MobSpawnType.COMMAND,
            MobSpawnType.BUCKET,
            MobSpawnType.DISPENSER,
            MobSpawnType.BREEDING,
            MobSpawnType.MOB_SUMMONED,
            MobSpawnType.CONVERSION,
            MobSpawnType.TRIGGERED,
            MobSpawnType.EVENT);

    @Test
    void fullDensityKeepsEverySpawnUnchanged() {
        for (MobSpawnType spawnType : MobSpawnType.values()) {
            for (MobCategory category : MobCategory.values()) {
                for (boolean boss : new boolean[] {false, true}) {
                    for (boolean rotating : new boolean[] {false, true}) {
                        for (double roll : new double[] {0.0D, 0.5D, 0.999999D}) {
                            assertFalse(MonsterSpawnDensity.shouldCancel(
                                            category, spawnType, boss, rotating, 1.0D, roll),
                                    spawnType + " " + category + " boss=" + boss + " rotating=" + rotating);
                        }
                    }
                }
            }
        }
    }

    @Test
    void zeroDensityCancelsEveryCoveredMonsterSpawn() {
        for (MobSpawnType spawnType : COVERED) {
            for (double roll : new double[] {0.0D, 0.5D, 0.999999D}) {
                assertTrue(MonsterSpawnDensity.shouldCancel(
                                MobCategory.MONSTER, spawnType, false, true, 0.0D, roll),
                        spawnType + " roll=" + roll);
            }
        }
    }

    @Test
    void probabilityKeepsRollsBelowDensityAndCancelsTheRest() {
        double density = 0.5D;
        assertFalse(MonsterSpawnDensity.shouldCancel(
                MobCategory.MONSTER, MobSpawnType.NATURAL, false, true, density, 0.0D));
        assertFalse(MonsterSpawnDensity.shouldCancel(
                MobCategory.MONSTER, MobSpawnType.NATURAL, false, true, density, 0.499999D));
        assertTrue(MonsterSpawnDensity.shouldCancel(
                MobCategory.MONSTER, MobSpawnType.NATURAL, false, true, density, 0.5D));
        assertTrue(MonsterSpawnDensity.shouldCancel(
                MobCategory.MONSTER, MobSpawnType.NATURAL, false, true, density, 0.999999D));

        // The smallest tunable step is 1%; the boundary must not be rounded away.
        assertFalse(MonsterSpawnDensity.shouldCancel(
                MobCategory.MONSTER, MobSpawnType.SPAWNER, false, true, 0.01D, 0.009999D));
        assertTrue(MonsterSpawnDensity.shouldCancel(
                MobCategory.MONSTER, MobSpawnType.SPAWNER, false, true, 0.01D, 0.01D));
    }

    @Test
    void playerCausedSpawnTypesAreNeverCovered() {
        for (MobSpawnType spawnType : PLAYER_CAUSED) {
            assertFalse(MonsterSpawnDensity.isCovered(spawnType), spawnType.name());
            assertFalse(MonsterSpawnDensity.shouldCancel(
                            MobCategory.MONSTER, spawnType, false, true, 0.0D, 0.0D),
                    spawnType.name());
        }
        assertFalse(MonsterSpawnDensity.isCovered(null));
    }

    @Test
    void bossesNonMonstersAndOtherDimensionsAreNeverSuppressed() {
        for (MobSpawnType spawnType : COVERED) {
            assertFalse(MonsterSpawnDensity.shouldCancel(
                    MobCategory.MONSTER, spawnType, true, true, 0.0D, 0.0D), spawnType + " boss");
            assertFalse(MonsterSpawnDensity.shouldCancel(
                    MobCategory.CREATURE, spawnType, false, true, 0.0D, 0.0D), spawnType + " creature");
            assertFalse(MonsterSpawnDensity.shouldCancel(
                    MobCategory.MONSTER, spawnType, false, false, 0.0D, 0.0D), spawnType + " non-rotating");
        }
    }

    @Test
    void densityAboveOneAlsoChangesNothing() {
        for (double density : new double[] {1.0D, 1.5D, 2.0D}) {
            assertFalse(MonsterSpawnDensity.shouldCancel(
                    MobCategory.MONSTER, MobSpawnType.SPAWNER, false, true, density, 0.999999D));
        }
    }

    @Test
    void everyCoveredSourceIsExactlyTheDocumentedSet() {
        for (MobSpawnType spawnType : MobSpawnType.values()) {
            if (COVERED.contains(spawnType)) {
                assertTrue(MonsterSpawnDensity.isCovered(spawnType), spawnType.name());
            } else {
                assertFalse(MonsterSpawnDensity.isCovered(spawnType), spawnType.name());
            }
        }
    }
}
