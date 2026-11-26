package dimblend.blocks.compat.rail;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * 某个玩家挂起的一个端点选择（扳手第一次右键记下的那一端）。
 *
 * <p>刻意不带维度：载荷本身已经带维度键，客户端按"维度不符就整份丢掉"处理。</p>
 */
public record TrainTubeSelection(UUID subLevel, BlockPos pos) {

    public TrainTubeSelection {
        Objects.requireNonNull(subLevel, "subLevel");
        Objects.requireNonNull(pos, "pos");
    }
}
