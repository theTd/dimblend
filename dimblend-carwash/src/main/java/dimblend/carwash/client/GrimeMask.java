package dimblend.carwash.client;

import java.util.Random;

/**
 * 镂空贴图的像素运算（纯函数，无 Minecraft 依赖）：随机去掉指定比例的像素，再按需去掉上/下半。
 * 像素为 NativeImage 的 ABGR 整数，0 即全透明。
 */
public final class GrimeMask {

    /** 额外清掉的半边（纹理坐标系：第 0 行在上）。 */
    public enum Clear {
        NONE,
        /** 去掉下半（保留上半）：车架侧面的泥。 */
        LOWER_HALF,
        /** 去掉上半（保留下半）：上方非实心格侧面的碎石。 */
        UPPER_HALF
    }

    /**
     * @param pixels         源像素，行优先，长度 width*height（不修改）
     * @param removeFraction 随机去掉的像素比例；去掉个数 = round(总数 × 比例)，精确计数
     * @return 新数组
     */
    public static int[] apply(int[] pixels, int width, int height, long seed, double removeFraction, Clear clear) {
        int count = width * height;
        if (pixels.length != count) {
            throw new IllegalArgumentException("pixel count " + pixels.length + " != " + width + "x" + height);
        }
        int[] out = pixels.clone();
        int toRemove = (int) Math.round(count * Math.max(0.0, Math.min(1.0, removeFraction)));
        // 部分 Fisher–Yates：前 toRemove 个位置即随机选中的像素
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        Random random = new Random(seed);
        for (int i = 0; i < toRemove; i++) {
            int j = i + random.nextInt(count - i);
            int picked = order[j];
            order[j] = order[i];
            order[i] = picked;
            out[picked] = 0;
        }
        int half = height / 2;
        if (clear == Clear.LOWER_HALF) {
            clearRows(out, width, half, height);
        } else if (clear == Clear.UPPER_HALF) {
            clearRows(out, width, 0, half);
        }
        return out;
    }

    /**
     * 分档镂空比例：第 1 档为 {@code from}，第 {@code levels} 档为 {@code to}，中间线性。
     *
     * @param level 1..levels
     */
    public static double ladderFraction(double from, double to, int level, int levels) {
        if (levels <= 1) {
            return from;
        }
        return from + (to - from) * (level - 1) / (levels - 1);
    }

    private static void clearRows(int[] pixels, int width, int fromRow, int toRow) {
        for (int y = fromRow; y < toRow; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = 0;
            }
        }
    }

    private GrimeMask() {
    }
}
