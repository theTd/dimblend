package dimblend.radio.server;

import dimblend.radio.RadioState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * 状态变更事件扇出：RadioState（纯数据）→ 广播。独立类以切断
 * RadioState ↔ RadioSync 的循环依赖（RadioSync 读 RadioState，RadioState 回调广播）。
 */
public final class RadioEvents {
    public static void changed(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.JUKEBOX)) {
            return;
        }
        RadioSync.broadcast(level, pos);
    }

    private RadioEvents() {
    }
}
