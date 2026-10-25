package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import dimblend.experience.exploration.RotatingMonsterCap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A12 rotation-dimension monster density: the global cap half of the percentage
 * (2026-10-21 user decision; 2026-10-26 extension from "natural cap only" to one
 * density percentage for every covered spawn source).
 *
 * <p>Wraps {@code NaturalSpawner.spawnForChunk}'s call to
 * {@code SpawnState.canSpawnForCategory}. In the rotating dimension, for MONSTER,
 * the global cap is scaled by the density {@code p} (the same value the tuning panel
 * shows as 0-100%). The per-spawn probability is applied by
 * {@code MonsterDensitySpawnGuard} on {@code FinalizeSpawnEvent}; both natural and
 * structure-override spawns pass through it. The two effects do not multiply: the
 * scaled cap is a population ceiling, the roll is the per-mob survival probability.
 * At cap-bound equilibrium the population is {@code scaledCap ≈ p·C}; at rate-bound
 * equilibrium it is {@code p·R}; both are ≈ {@code p} of the vanilla population.
 *
 * <p>{@code p >= 1} takes the original path exactly. {@code p = 0} makes the cap 0,
 * so natural spawning stops. The per-chunk cap is rounded up, so {@code p > 0} keeps a
 * non-zero per-chunk cap; the final vanilla integer division can still truncate a very
 * small spawnable area to 0 (that area would have a tiny vanilla cap too). Other
 * categories and other dimensions always take the original path.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerCapMixin {

    @WrapOperation(
            method = "spawnForChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;canSpawnForCategory(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/ChunkPos;)Z"))
    private static boolean dimblend$scaleRotatingMonsterCap(NaturalSpawner.SpawnState state, MobCategory category, ChunkPos chunkPos,
            Operation<Boolean> original, ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState enclosingState,
            boolean spawnFriendlies, boolean spawnMonsters, boolean forceSpawn) {
        if (category != MobCategory.MONSTER) {
            return original.call(state, category, chunkPos);
        }
        double density = Config.ROTATING_MONSTER_DENSITY.get();
        if (density >= 1.0D || !RotatingDimension.is(level)) {
            return original.call(state, category, chunkPos);
        }
        if (state.getMobCategoryCounts().getInt(category)
                >= RotatingMonsterCap.scaledCap(category.getMaxInstancesPerChunk(), state.getSpawnableChunkCount(), density)) {
            return false;
        }
        return original.call(state, category, chunkPos);
    }
}
