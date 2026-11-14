package dimblend.experience.compat.lootjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 拾取图标诊断的纯逻辑测试（{@link IconSamplingWindow} 与
 * {@link LootJournalIconDiagnostics#describe}）。
 *
 * <p>钉住的是两条会直接毁掉诊断价值的契约：</p>
 * <ol>
 *   <li>限频窗口必须严格 2 秒一行，但<b>时钟回拨不能让它永久静默</b>——诊断静默失效比多打
 *       一行日志糟得多（{@code System.currentTimeMillis} 会被 NTP 校时往回拨）；</li>
 *   <li>日志正文的字段口径固定：{@code source} / {@code depthTest} / {@code color} /
 *       {@code poseZ} / {@code hideGui}，颜色缺失记 {@code n/a} 而不是猜 0（记 0 会被误读成
 *       「被调成透明」这一具体事故）。</li>
 * </ol>
 */
class LootJournalIconDiagnosticsTest {

    @Test
    void firstSampleAlwaysPasses() {
        IconSamplingWindow window = new IconSamplingWindow(2_000L);
        assertTrue(window.tryAcquire(1_000L));
    }

    @Test
    void samplesInsideWindowAreSuppressed() {
        IconSamplingWindow window = new IconSamplingWindow(2_000L);
        assertTrue(window.tryAcquire(10_000L));
        assertFalse(window.tryAcquire(10_001L));
        assertFalse(window.tryAcquire(11_999L));
    }

    @Test
    void sampleAtWindowBoundaryPasses() {
        IconSamplingWindow window = new IconSamplingWindow(2_000L);
        assertTrue(window.tryAcquire(10_000L));
        assertTrue(window.tryAcquire(12_000L));
    }

    @Test
    void clockGoingBackwardsDoesNotMuteTheWindowForever() {
        IconSamplingWindow window = new IconSamplingWindow(2_000L);
        assertTrue(window.tryAcquire(100_000L));
        // 时钟回拨一小时：now - last 为负，若按差值比较会永久小于窗口长度
        assertTrue(window.tryAcquire(100_000L - 3_600_000L));
        assertFalse(window.tryAcquire(100_000L - 3_600_000L + 1L));
    }

    @Test
    void zeroWindowPassesEverySample() {
        IconSamplingWindow window = new IconSamplingWindow(0L);
        assertTrue(window.tryAcquire(500L));
        assertTrue(window.tryAcquire(500L));
    }

    @Test
    void describeReportsEveryDiagnosticField() {
        String line = LootJournalIconDiagnostics.describe(
                "net.raphimc.immediatelyfast.feature.batching.HudBatchingBufferSource",
                true,
                new float[] {1.0F, 1.0F, 1.0F, 0.5F},
                240.0F,
                false);

        assertEquals("source=net.raphimc.immediatelyfast.feature.batching.HudBatchingBufferSource"
                + " depthTest=true color=(1.000,1.000,1.000,0.500) poseZ=240.0 hideGui=false", line);
    }

    @Test
    void describeMarksMissingColorAsNotAvailableInsteadOfZero() {
        assertEquals("source=null depthTest=false color=n/a poseZ=0.0 hideGui=true",
                LootJournalIconDiagnostics.describe(null, false, null, 0.0F, true));
        assertEquals("source=null depthTest=false color=n/a poseZ=0.0 hideGui=true",
                LootJournalIconDiagnostics.describe(null, false, new float[] {1.0F, 1.0F, 1.0F}, 0.0F, true));
    }
}
