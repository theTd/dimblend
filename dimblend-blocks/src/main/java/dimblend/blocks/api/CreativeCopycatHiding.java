package dimblend.blocks.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 创造伪装方块隐藏状态的对外查询口（dimblend-carwash 等兄弟 mod 只许依赖本 api 包，
 * 不 import 实现层）。BE 未实现 {@link CreativeCopycatHidable}（非创造伪装方块，
 * 或 Copycats+/Create 缺席）一律视为未隐藏，调用永远安全。
 */
public final class CreativeCopycatHiding {

    public static boolean isHidden(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CreativeCopycatHidable hidable
                && hidable.isCopycatHidden();
    }

    /** 幂等；目标 BE 不支持隐藏时返回 false。 */
    public static boolean setHidden(Level level, BlockPos pos, boolean hidden) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof CreativeCopycatHidable hidable)) {
            return false;
        }
        hidable.setCopycatHidden(hidden);
        return true;
    }

    private CreativeCopycatHiding() {
    }
}
