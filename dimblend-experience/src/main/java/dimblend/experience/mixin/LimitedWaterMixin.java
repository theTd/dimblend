package dimblend.experience.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.llamalad7.mixinextras.sugar.Local;

import dimblend.experience.Config;
import dimblend.experience.exploration.LimitedWaterRules;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * G3 有限水写入点：原版 {@code Level#setBlock(4 参)}（{@code ServerLevel} 未重写该方法，
 * 3 参与 {@code setBlockAndUpdate} 均委托至此；桶、管道、发射器、机械手、冰融化、
 * 水流成池最终都收敛到这里）。
 *
 * <p>在方法入口改写 {@code state} 参数（{@code @ModifyVariable}），不取消、不重入：
 * 后续逻辑与其它模组的注入看到的就是改写后的 water7。热路径排序：纯水源判定（非水
 * 写入到此即止）→ 服务端 → 配置已加载且开关开 → 维度 → {@link LimitedWaterRules}。</p>
 */
@Mixin(Level.class)
public abstract class LimitedWaterMixin {

    @ModifyVariable(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            argsOnly = true)
    private BlockState dimblend$limitWater(BlockState state, @Local(argsOnly = true) BlockPos pos) {
        if (!LimitedWaterRules.isWaterSource(state)) {
            return state;
        }
        if (!((Object) this instanceof ServerLevel level)) {
            return state;
        }
        if (!Config.isLoaded() || !Config.LIMITED_WATER.get() || !RotatingDimension.is(level)) {
            return state;
        }
        BlockState replacement = LimitedWaterRules.replacementFor(level, pos);
        return replacement != null ? replacement : state;
    }
}
