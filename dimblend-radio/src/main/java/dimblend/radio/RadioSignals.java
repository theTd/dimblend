package dimblend.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * 真值表与信号读取：唯一真相源。
 *
 * <p>顶部（正上方邻格朝下读）：{@code level.getSignal(pos.above(), Direction.UP)}。
 * 0 或 15 → 原版旁路（不接管）；1..14 → 电台号（台号 = 文件夹名）。</p>
 *
 * <p>侧面（水平四面 max，忽略底部与顶部）：
 * 0 → 停播；1..15 → 音量 10%..150% 线性（side*10）。</p>
 *
 * <p>前提：空盘（无唱片）。有盘时电台永不启动；电台播中被塞盘则停播让位原版。</p>
 */
public final class RadioSignals {
    public static final int STATION_MIN = 1;
    public static final int STATION_MAX = 14;

    /** 侧面音量：1..15 → 10%..150%（线性，side*10）。0 由调用方判停。 */
    public static int volumePercent(int sideSignal) {
        return sideSignal * 10;
    }

    /** PCM 预放大倍率：100%→1.0，150%→1.5。 */
    public static float pcmBoost(int sideSignal) {
        return volumePercent(sideSignal) / 100.0f;
    }

    public static boolean isBypass(int topSignal) {
        return topSignal == 0 || topSignal == 15;
    }

    public static boolean isStation(int topSignal) {
        return topSignal >= STATION_MIN && topSignal <= STATION_MAX;
    }

    public static int readTop(Level level, BlockPos pos) {
        return level.getSignal(pos.above(), Direction.UP);
    }

    /** 水平四面 max：N/S/W/E。底部（DOWN）与顶部（UP）一律忽略。 */
    public static int readSide(Level level, BlockPos pos) {
        int side = 0;
        side = Math.max(side, level.getSignal(pos.north(), Direction.NORTH));
        side = Math.max(side, level.getSignal(pos.south(), Direction.SOUTH));
        side = Math.max(side, level.getSignal(pos.west(), Direction.WEST));
        side = Math.max(side, level.getSignal(pos.east(), Direction.EAST));
        return side;
    }

    private RadioSignals() {
    }
}
