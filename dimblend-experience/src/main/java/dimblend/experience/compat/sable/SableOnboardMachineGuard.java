package dimblend.experience.compat.sable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

/**
 * 「载具上不该存在的机器」的运行时清除（2026-11-25）。
 *
 * <p>放置侧禁令（{@link SablePlacementBanRules}）只覆盖 {@code BlockItem#place}：
 * 蓝图炮、{@code setblock}、结构生成、装置解体等路径都能把风车轴承/水车塞进子层级。
 * 因此再加一道每 tick 自愈：服务端发现这类方块实体位于任一子层级内就按自身掉落规则破坏。
 * 判定只做 {@code instanceof} 与「是否在子层级内」，对 Sable 的引用仍放在惰性持有类里。</p>
 */
public final class SableOnboardMachineGuard {

    /** 返回 true = 已破坏，调用方应中止本 tick 的原逻辑。 */
    public static boolean destroyIfOnSubLevel(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level) || !ModList.get().isLoaded("sable")) {
            return false;
        }
        if (blockEntity.isRemoved() || !SableAccess.isInsideSubLevel(blockEntity)) {
            return false;
        }
        // 走方块自身的掉落规则（容器内容物一并弹出），与"被拆"一致。
        level.destroyBlock(blockEntity.getBlockPos(), true);
        return true;
    }

    /** Only resolved behind the sable gate; never loads Sable types when the mod is absent. */
    private static final class SableAccess {
        private static boolean isInsideSubLevel(BlockEntity blockEntity) {
            return dev.ryanhcode.sable.Sable.HELPER.getContaining(blockEntity) != null;
        }
    }

    private SableOnboardMachineGuard() {
    }
}
