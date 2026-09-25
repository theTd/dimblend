package dimblend.experience.exploration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * G3 孤立源水降级（服务端，rotating 维度内）：凡经 {@code Level#setBlock} 写入世界的
 * 纯水源（water8，即 {@code Blocks.WATER} 且 {@code LEVEL=0}），若其水平四邻中源水
 * 格数 <2 则改写为流动 water7（{@code LEVEL=1}，即 amount 7 非下落）；≥2 才保留源水。
 *
 * <p>注：water8/7 指流体 amount 8（源）/7（最高流动），legacy 方块值分别为
 * {@code LEVEL=0/1}（见 {@code FlowingFluid#getLegacyLevel}：源→0，非下落 amount
 * n→{@code 8-n}）。含水方块只作邻居计数外的存在——既不降级（布尔含水无法表示
 * water7），也不计入四邻源水数（只数纯 {@code Blocks.WATER} 源）。</p>
 *
 * <p>覆盖渠道：桶（手持/发射器）、Create 管道/水管、冰融化、流体 tick 自然成池等——
 * 它们最终都收敛到 {@code Level#setBlock(4 参)}（{@code ServerLevel} 未重写该方法，
 * 3 参/{@code setBlockAndUpdate} 均委托至此）。世界生成（{@code WorldGenRegion}）
 * 不走此路径，天然水体不受影响；已存在的源水不回扫，只拦新写入。</p>
 */
public final class IsolatedWaterRules {

    /** water7 目标态：纯水 amount 7 非下落（legacy {@code LEVEL=1}）。 */
    public static BlockState downgradedState() {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);
    }

    /** 是否为纯水源 water8（{@code Blocks.WATER} 且 {@code LEVEL=0}）。 */
    public static boolean isWaterSource(BlockState state) {
        return state.is(Blocks.WATER) && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * 若新写入态是孤立纯水源，返回应改写的 water7 态；否则返回 {@code null}（放行原态）。
     */
    public static BlockState downgradeIfIsolated(LevelReader level, BlockPos pos, BlockState newState) {
        if (!isWaterSource(newState)) {
            return null;
        }
        if (IsolatedWaterMath.keepSource(countSourceNeighbors(level, pos))) {
            return null;
        }
        return downgradedState();
    }
    /** 数水平四邻中的纯水源格数（只数 {@code Blocks.WATER} 源，不含含水方块）。 */
    public static int countSourceNeighbors(LevelReader level, BlockPos pos) {
        int count = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isWaterSource(level.getBlockState(pos.relative(direction)))) {
                count++;
            }
        }
        return count;
    }

    private IsolatedWaterRules() {
    }
}
