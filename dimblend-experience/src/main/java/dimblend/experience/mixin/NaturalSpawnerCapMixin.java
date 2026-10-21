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
 * A12 旋转维度怪物密度压制（2026-10-21 用户拍板：密度体感约为原版两倍，压回一半）：
 * 包 NaturalSpawner.spawnForChunk 内对 SpawnState.canSpawnForCategory 的调用——
 * rotating 维度且类别为 MONSTER 时先按 Config.rotatingMonsterCapMultiplier 缩放全局
 * 上限，当前怪物数已达缩放上限则本轮不刷；未达上限仍交回原判定（原版全局+单玩家
 * 局部上限照跑）。其它类别、其它维度与倍率 1.0 时完全走原版。
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
        double multiplier = Config.ROTATING_MONSTER_CAP_MULTIPLIER.get();
        if (multiplier >= 1.0D || !RotatingDimension.is(level)) {
            return original.call(state, category, chunkPos);
        }
        if (state.getMobCategoryCounts().getInt(category)
                >= RotatingMonsterCap.scaledCap(category.getMaxInstancesPerChunk(), state.getSpawnableChunkCount(), multiplier)) {
            return false;
        }
        return original.call(state, category, chunkPos);
    }
}
