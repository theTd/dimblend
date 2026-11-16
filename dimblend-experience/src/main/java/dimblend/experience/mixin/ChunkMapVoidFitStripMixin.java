package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.sable.VoidFitStrip;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 结构空位不落盘：{@code ChunkMap#save} 在确认区块需要落盘后唯一调用一次
 * {@code ChunkSerializer#write}（{@code ChunkMap#save} 自身有 5 条早退/异常 {@code ireturn}，
 * 且 {@code saveChunkIfNeeded} 不会预判 {@code isUnsaved()}）。用 {@code @WrapOperation}
 * 精准包裹 {@code ChunkSerializer#write}，既避开干净区块早退时的无效剥/还，也通过
 * {@code try / finally} 保证序列化异常时内存中的拟合空位必然还原。
 *
 * <p>目标为原版类、无条件加载（Sable 缺席时 tracker 恒空，{@code hasAny} 早退零开销）。</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapVoidFitStripMixin {

    @WrapOperation(
            method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;"
            ),
            require = 1,
            allow = 1
    )
    private CompoundTag dimblend$writeWithoutFittedVoids(
            ServerLevel serverLevel,
            ChunkAccess chunk,
            Operation<CompoundTag> original
    ) {
        LongList stripped = VoidFitStrip.stripFittedVoids(serverLevel, chunk);
        try {
            return original.call(serverLevel, chunk);
        } finally {
            // ChunkMap#scheduleUnload 在真正卸载（pendingUnloads.remove 成功后）先把
            // LevelChunk#loaded 置为 false 再调用 save；该区块已不可复活，跳过空位还原。
            if (!stripped.isEmpty()
                    && !(chunk instanceof LevelChunk levelChunk
                            && !((LevelChunkLoadedAccessor) levelChunk).dimblend$isLoaded())) {
                VoidFitStrip.restoreFittedVoids(serverLevel, chunk, stripped);
            }
        }
    }
}
