package dimblend.experience.compat.yat;

import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 炼药锅方块态纯度的唯一读写口（YAT {@code BLOCK_PURITY}：{@code 0 = 未设置}，
 * 真实纯度写 {@code purity + 1}）。
 *
 * <p>写回一律走"取脏"（{@link WaterSourcePurityRules#mergeBlockPurity}）：一锅水被雨水、
 * 滴水或脏水源碰过之后就只会更脏，不会被打回纯净。只有方块本身就带该属性时才写
 * （空炼药锅/非水炼药锅没有这个属性，YAT 自己的代码也是先 {@code hasProperty} 再取值）。</p>
 */
public final class CauldronPurity {

    /** 当前锅的纯度；{@code -1} = 未设置/该方块没有该属性。 */
    public static int read(BlockState state) {
        if (!state.hasProperty(WaterPurity.BLOCK_PURITY)) {
            return -1;
        }
        return WaterSourcePurityRules.decodeBlockPurity(state.getValue(WaterPurity.BLOCK_PURITY));
    }

    /** 是否是装水的炼药锅（有 LEVEL 属性且是 {@code water_cauldron}）。 */
    public static boolean isWaterCauldron(BlockState state) {
        return state.is(Blocks.WATER_CAULDRON);
    }

    /**
     * 把一层水的纯度并入方块态：读当前值、按 {@code min} 合并、写回 {@code +1} 编码。
     * 只有"该方块存在该属性且确实还是炼药锅"时才动手。
     */
    public static void blend(Level level, BlockPos pos, int incoming) {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(WaterPurity.BLOCK_PURITY)) {
            return;
        }
        int existing = read(state);
        int merged = WaterSourcePurityRules.mergeBlockPurity(existing, incoming);
        int encoded = WaterSourcePurityRules.encodeBlockPurity(merged);
        if (state.getValue(WaterPurity.BLOCK_PURITY) == encoded) {
            return;
        }
        level.setBlockAndUpdate(pos, state.setValue(WaterPurity.BLOCK_PURITY, encoded));
    }

    private CauldronPurity() {
    }
}
