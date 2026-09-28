package dimblend.radio;

import dimblend.radio.server.RadioClock;
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
     * 是否该切歌：服务钟（{@link RadioClock} 毫秒）过 startMillis + 时长 + 5s 间隔。
     * 时长查服务端曲库（秒→毫秒）；查不到时长的 hash 按 3 分钟兜底，避免卡死单曲。
     * 不用 gameTime：掉刻时 gameTime 被拉长而音频按真实时间播，切歌会拖慢数倍。
     */
    public static boolean shouldAdvance(RadioState.Entry entry) {
        if (entry.trackHash() == null) {
            return true;
        }
        long lengthMillis = RadioCatalog.lengthTicks(entry.station(), entry.trackHash()) * 50L;
        return RadioClock.now() >= entry.startMillis() + lengthMillis + RadioState.GAP_TICKS * 50L;
    }

    private RadioControl() {
    }
}
