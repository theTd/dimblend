package dimblend.experience.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.sable.VoidFitExplosionPassthrough;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 爆炸射线穿过 {@code SableVoidFit} 拟合空位（见 {@link VoidFitExplosionPassthrough}）：
 * 在 {@code Explosion#explode()} 读取世界空间 {@code Level#getBlockState(BlockPos)} 处把受跟踪的
 * {@code structure_void} 映射为空气，使后续原版射线与 Sable {@code ExplosionMixin}（依赖局部变量
 * {@code blockstate.isAir()}）都能穿透防水壳直达 {@code SubLevel} 车厢方块，且不破坏世界空间的防水空位。
 *
 * <p>目标为原版类、无条件加载（不引用任何三方类型；Sable 缺席或非拟合格时单次标签判断早退）。</p>
 */
@Mixin(Explosion.class)
public abstract class ExplosionVoidFitPassthroughMixin {

    @WrapOperation(
            method = "explode()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1)
    private BlockState dimblend$maskFittedStructureVoidInRay(
            Level instance, BlockPos pos, Operation<BlockState> original) {
        return VoidFitExplosionPassthrough.maskFittedStructureVoid(
                instance, pos, original.call(instance, pos));
    }
}
