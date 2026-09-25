package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.compat.VoidscapeBand;
import dimblend.time.ServerBandTime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Natural monster spawn reads {@code getSkyDarken} / {@code isDarkEnoughToSpawn},
 * not {@code LivingEntity.tick}. Push the band time stack around the spawn
 * attempt so End's 18000 lock is night for light checks and endermen keep
 * appearing after idle despawn.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @WrapMethod(
            method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V")
    private static void dimblend$bandTimeSpawn(
            MobCategory category,
            ServerLevel level,
            ChunkAccess chunk,
            BlockPos pos,
            NaturalSpawner.SpawnPredicate filter,
            NaturalSpawner.AfterSpawnCallback callback,
            Operation<Void> original
    ) {
        ServerBandTime.run(level, pos, () -> VoidscapeBand.run(level, pos,
                () -> original.call(category, level, chunk, pos, filter, callback)));
    }
}
