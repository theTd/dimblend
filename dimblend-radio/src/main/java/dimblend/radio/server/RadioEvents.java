package dimblend.radio.server;

import dimblend.radio.RadioState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 状态变更事件扇出：RadioState（纯数据）→ 广播。独立类以切断
 * RadioState ↔ RadioSync 的循环依赖（RadioSync 读 RadioState，RadioState 回调广播）。
 */
public final class RadioEvents {
    /**
     * 广播该位置的当前电台状态（有状态 → 播放态；已删 → playing=false 停播包）。
     *
     * <p>不按“该位置还是不是唱片机”过滤：唱片机被拆、被炸、被 Sable 结构组装/解体挪走后，
     * {@link RadioState} 删状态时方块已不是唱片机，此时更要把停播广播出去，否则客户端
     * 一直播到曲终（结构组装时旧位置与新位置两台同时响）。调用方只在状态真变化时调用
     * （删除仅在确有旧状态时），不会刷屏。</p>
     */
    public static void changed(ServerLevel level, BlockPos pos) {
        RadioSync.broadcast(level, pos);
    }

    private RadioEvents() {
    }
}
