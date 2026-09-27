package dimblend.radio.client;

/**
 * 按曲余量线性预放大（纯计算，16bit 小端，原地）。
 *
 * <p>side 11~15 的 &gt;100% 只能在 PCM 里给（引擎通道增益封顶 1.0）。每首只放大到峰值刚好不越
 * {@link #CEILING} 为止（最多 {@code maxBoost}）：纯线性，不削波、不压缩。满幅母带倍率就是 1.0，
 * 这类曲子 side 11~15 与 side 10 同响——干净优先，不靠限幅硬撑响度。</p>
 *
 * <p>解码时做一次；各档音量只调通道增益 {@link #channelGain}，变音量（含跨 100%）不换 PCM、
 * 不重建实例、不断音。side 10 的通道增益恰为 1/倍率，听到的永远是原曲响度。</p>
 */
public final class PcmHeadroom {
    /** 预放大后峰值上限 ≈ -0.3 dBFS：给 OpenAL 重采样的样本间峰值留一点余量。 */
    static final int CEILING = 31650;

    /**
     * 线性放大到本曲余量上限，返回实际倍率。
     *
     * @param pcm      16bit 小端 PCM（原地改写）
     * @param maxBoost 倍率上限（音量上限 150% → 1.5）
     * @return 实际倍率，≥1；满幅母带为 1（数据不动）
     */
    public static float applyMaxBoost(byte[] pcm, float maxBoost) {
        float boost = boostFor(peak(pcm), maxBoost);
        if (boost > 1.0f) {
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                int v = Math.max(-32768, Math.min(32767, Math.round(s * boost)));
                pcm[i] = (byte) v;
                pcm[i + 1] = (byte) (v >> 8);
            }
        }
        return boost;
    }

    /** 峰值 {@code peak} 的曲子能干净放大的倍率：{@code clamp(CEILING/peak, 1, maxBoost)}。 */
    static float boostFor(int peak, float maxBoost) {
        if (peak <= 0) {
            return maxBoost; // 全静音：放多大都不越顶
        }
        return Math.max(1.0f, Math.min(maxBoost, (float) CEILING / peak));
    }

    /**
     * 通道增益：{@code min(1, 音量% / 100 / 倍率)}。倍率已在 PCM 里，side 10（100%）恰好还原原曲；
     * 超出本曲余量的档位封顶在 1.0（与上限同响）。
     */
    public static float channelGain(int volumePercent, float boost) {
        return Math.min(1.0f, volumePercent / 100.0f / boost);
    }

    static int peak(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs(s));
        }
        return peak;
    }

    private PcmHeadroom() {
    }
}
