package dimblend.mixin.aether;

import com.llamalad7.mixinextras.sugar.Local;
import dimblend.compat.AetherSpawnChunkScan;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Aechor Plant natural-spawn check ({@code inRadiusOfFlowers(level, pos, 10, 40)}: deterrents
 * within 10 blocks, or deterrents on enchanted grass within 40, scanned over a 40-chunk radius):
 * scan only the chunks the larger block radius can reach, and never wait for a chunk to load.
 * See {@link AetherSpawnChunkScan}.
 */
@Mixin(targets = "com.aetherteam.aether.entity.monster.AechorPlant")
public abstract class AechorPlantSpawnFlowerScanMixin {
    @ModifyArg(
            method = "inRadiusOfFlowers",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/ChunkPos;rangeClosed(Lnet/minecraft/world/level/ChunkPos;I)Ljava/util/stream/Stream;"
            ),
            index = 1
    )
    private static int dimblend$scanOnlyReachableChunks(int scannedChunks,
                                                         @Local(argsOnly = true, ordinal = 0) int nearRadius,
                                                         @Local(argsOnly = true, ordinal = 1) int farRadius) {
        return AetherSpawnChunkScan.scanRadius(scannedChunks, Math.max(nearRadius, farRadius));
    }

    @Redirect(
            method = "inRadiusOfFlowers",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelAccessor;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"
            )
    )
    private static ChunkAccess dimblend$loadedChunkOnly(LevelAccessor level, int x, int z, ChunkStatus status,
                                                       boolean require) {
        return AetherSpawnChunkScan.chunkIfLoaded(level, x, z, status, require);
    }
}
