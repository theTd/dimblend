package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlock;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;

import dimblend.experience.compat.create.SteamEngineExhaustAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 排气 32 秒里不许轴被"重新耦合"成动力轴。
 *
 * <p>耦合点（反汇编核实）：{@code ShaftBlock#pickCorrectShaftType} 放置时调
 * {@code PoweredShaftBlock#stillValid(state, level, pos)}，为 true 就换成
 * {@code POWERED_SHAFT}；{@code canSurvive} 也用它，所以动力轴被断掉后玩家在原位
 * 补一根传动杆，原版会立刻变回动力轴、引擎下一 tick 又转起来——32 秒窗口就白烧了。</p>
 *
 * <p>这里在 {@code stillValid} 的 RETURN 上压掉：遍历与原版同一个口径（沿非轴方向的 2 格外
 * 找到蒸汽引擎，且 {@code SteamEngineBlock.getShaftPos} 正好指回本格），若那台引擎正在排气
 * → 返回 false。于是这 32 秒里放下去的传动杆保持普通杆形态；窗口结束由
 * {@code SteamEngineOverload#finishExhaust} 换回动力轴。</p>
 */
@Mixin(PoweredShaftBlock.class)
public abstract class PoweredShaftBlockExhaustMixin {

    @Inject(method = "stillValid", at = @At("RETURN"), cancellable = true, require = 1, allow = 2)
    private static void dimblend$refuseCouplingWhileExhausting(BlockState shaftState, LevelReader level,
            BlockPos shaftPos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        Direction.Axis axis = shaftState.getValue(RotatedPillarBlock.AXIS);
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == axis) {
                continue;
            }
            BlockPos enginePos = shaftPos.relative(direction, 2);
            BlockState engineState = ((BlockGetter) level).getBlockState(enginePos);
            if (!(engineState.getBlock() instanceof SteamEngineBlock)) {
                continue;
            }
            if (!SteamEngineBlock.getShaftPos(engineState, enginePos).equals(shaftPos)) {
                continue;
            }
            if (SteamEngineExhaustAccess.isExhaustingAt(level, enginePos)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
