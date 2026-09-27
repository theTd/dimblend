package dimblend.radio;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/**
 * 空盘判定 + 服务端曲终推进所需的曲库信息（服务端只管 hash 与时长，不读音频字节）。
 *
 * <p>服务端曲库 = 各客户端曲库的目录名并集的保守估计：实际只存“站台有哪些 hash”，
 * 由客户端上报（{@code radio_hello}）或运维约定保证一致；缺失 hash 的客户端自行跳过。</p>
 */
public final class RadioControl {
    /** 空盘：BE 是唱片机且槽空。songPlayer 状态不看（radio 播时它恒 null）。 */
    public static boolean isEmpty(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.JUKEBOX)) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof JukeboxBlockEntity jukebox && jukebox.getTheItem().isEmpty();
    }

    /**
     * 是否该切歌：startTick + 时长 + 5s 间隔已过。时长查服务端曲库（秒→tick）；
     * 查不到时长的 hash 按 3 分钟兜底，避免卡死单曲。
     */
    public static boolean shouldAdvance(ServerLevel level, BlockPos pos, RadioState.Entry entry) {
        if (entry.trackHash() == null) {
            return true;
        }
        long lengthTicks = RadioCatalog.lengthTicks(entry.station(), entry.trackHash());
        return level.getGameTime() >= entry.startTick() + lengthTicks + RadioState.GAP_TICKS;
    }

    private RadioControl() {
    }
}
