package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.CDGConfig;
import com.jesz.createdieselgenerators.world.OilChunksSavedData;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.cdg.CdgNewOilChunksSavedData;
import dimblend.experience.compat.cdg.CdgOilBalance;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 新生成区域的 CDG 原油区块削减：取消自然生成的无限油田，并将有限油田储量折算为 10%。
 * 既有存档已生成区块不命中 {@link CdgNewOilChunksSavedData#isNewChunk}，保持原样。
 */
@Mixin(OilChunksSavedData.class)
public abstract class OilChunksSavedDataMixin {

    @Shadow(remap = false)
    private Map<ChunkPos, Integer> chunks;

    @Shadow(remap = false)
    private ServerLevel level;

    @Inject(
            method = "getChunkOilAmount(Lnet/minecraft/world/level/ChunkPos;)I",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1)
    private void dimblend$preventStoredNewChunkInfiniteUpgrade(
            ChunkPos chunk, CallbackInfoReturnable<Integer> cir) {
        if (!this.chunks.containsKey(chunk)
                || !CdgNewOilChunksSavedData.isNewChunk(this.level, chunk)) {
            return;
        }
        int stored = this.chunks.get(chunk);
        if (stored != Integer.MAX_VALUE) {
            cir.setReturnValue(CdgOilBalance.sanitizeStoredNewChunkAmount(stored));
        }
    }

    @WrapOperation(
            method = "getBaseOilAmount(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)I",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/ModConfigSpec$ConfigValue;get()Ljava/lang/Object;",
                    ordinal = 6),
            remap = false,
            require = 1,
            allow = 1)
    private static Object dimblend$suppressInfiniteThresholdOnNewChunk(
            ModConfigSpec.ConfigValue<?> instance,
            Operation<Object> original,
            ServerLevel level,
            ChunkPos chunk) {
        if (instance == CDGConfig.OIL_CHUNK_INFINITE_THRESHOLD
                && CdgNewOilChunksSavedData.isNewChunk(level, chunk)) {
            return Integer.MAX_VALUE;
        }
        return original.call(instance);
    }

    @Inject(
            method = "getBaseOilAmount(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)I",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 1)
    private static void dimblend$scaleNewChunkBaseOilAmount(
            ServerLevel level, ChunkPos chunk, CallbackInfoReturnable<Integer> cir) {
        if (!CdgNewOilChunksSavedData.isNewChunk(level, chunk)) {
            return;
        }
        cir.setReturnValue(CdgOilBalance.scaleNewChunkBaseAmount(
                cir.getReturnValueI(), CDGConfig.OIL_CHUNK_INFINITE_THRESHOLD.get()));
    }
}
