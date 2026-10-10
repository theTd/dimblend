package dimblend.experience.mixin;

import it.unimi.dsi.fastutil.longs.LongList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.experience.compat.sable.VoidFitStrip;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 结构空位不落盘：{@code ChunkMap#save} 是 1.21.1 区块序列化的唯一漏斗
 * （{@code ChunkSerializer#write} 仅此处调用），在其 HEAD 把拟合空位剥成空气、
 * RETURN 还原。剥/还窗口在单次 save 内、主线程上，无并发观察者；{@code save} 内部
 * 自行 catch 序列化异常，RETURN 注入必然执行，还原不会被跳过。
 *
 * <p>目标为原版类、无条件加载（Sable 缺席时 tracker 恒空，{@code hasAny} 早退零开销）。</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapVoidFitStripMixin {

    @Shadow
    @Final
    ServerLevel level;

    /** HEAD 剥掉的格，RETURN 还原。save 不可重入且单线程，实例字段足够。 */
    @Unique
    private LongList dimblend$strippedVoids;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"))
    private void dimblend$stripFittedVoids(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        dimblend$strippedVoids = VoidFitStrip.stripFittedVoids(level, chunk);
    }

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("RETURN"))
    private void dimblend$restoreFittedVoids(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (dimblend$strippedVoids != null) {
            VoidFitStrip.restoreFittedVoids(level, chunk, dimblend$strippedVoids);
            dimblend$strippedVoids = null;
        }
    }
}
