package dimblend.experience.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;

import dimblend.experience.compat.simurail.TrainBrakeSustainRules;
import dimblend.experience.compat.simurail.client.TrainBrakeSustainAudioStream;
import org.junit.jupiter.api.Test;

/**
 * 刹车持续鸣响的 PCM 流算术（2026-11-25 LOCKED #4 / Slice 4B）。
 *
 * <p>只钉纯算术：75% 音节循环窗口、30ms 交叉淡化无台阶、跌破 2 m/s 后的自然尾音 EOF、
 * 松闸 5ms 归零。素材实测 {@code 239823} 帧 / 48 kHz / 单声道（{@code report-w1.md §8.3}）。</p>
 */
class TrainBrakeSustainAudioStreamTest {

    /** 素材帧数：4.9963s @ 48 kHz。 */
    private static final int SAMPLE_FRAMES = 239_823;
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0F, 16, 1, true, false);
    private static final int FRAME_BYTES = 2;
    /** 1 秒的块：与 {@code Channel#attachBufferStream} 的 {@code calculateBufferSize(format, 1)} 等值。 */
    private static final int ONE_SECOND = 96_000;

    @Test
    void loopWindowSitsAt75PercentWithA1440FrameCrossfade() {
        TrainBrakeSustainRules.LoopWindow window =
                TrainBrakeSustainRules.loopWindow(SAMPLE_FRAMES, FORMAT.getSampleRate());
        assertEquals((int) (SAMPLE_FRAMES * TrainBrakeSustainRules.LOOP_START_FRACTION), window.start());
        assertEquals((int) (SAMPLE_FRAMES * TrainBrakeSustainRules.LOOP_END_FRACTION), window.end());
        assertEquals(1440, window.crossfade(), "30ms @ 48kHz");
        assertEquals(240, TrainBrakeSustainRules.stopRampFrames(48_000.0F), "5ms @ 48kHz");
        double startSeconds = window.start() / 48_000.0D;
        double endSeconds = window.end() / 48_000.0D;
        assertEquals(3.56D, startSeconds, 0.01D, "循环窗口起点 ≈ 3.56s");
        assertEquals(3.92D, endSeconds, 0.01D, "循环窗口终点 ≈ 3.92s，后 25% 是素材自带衰减");
    }

    @Test
    void sustainingLoopsForeverWithoutHittingEof() throws Exception {
        TrainBrakeSustainAudioStream stream = new ToneStream(SAMPLE_FRAMES).open();
        TrainBrakeSustainRules.LoopWindow window = stream.loopWindow();
        long emitted = 0L;
        for (int second = 0; second < 25; second++) {
            ByteBuffer buffer = stream.read(ONE_SECOND);
            assertEquals(ONE_SECOND, buffer.remaining(), "持续段每次都应送满 1 秒");
            assertTrue(buffer.isDirect(), "OpenAL 只吃直接缓冲");
            emitted += buffer.remaining() / FRAME_BYTES;
        }
        assertEquals(25L * 48_000L, emitted);
        assertFalse(stream.atEnd(), "持续段绝不能 EOF");
        assertTrue(stream.position() >= window.start() && stream.position() < window.end(),
                "游标必须一直留在循环窗口内，实际 " + stream.position());
    }

    @Test
    void releasingSustainPlaysTheNaturalTailThenEnds() throws Exception {
        TrainBrakeSustainAudioStream stream = new ToneStream(SAMPLE_FRAMES).open();
        for (int second = 0; second < 6; second++) {
            stream.read(ONE_SECOND);
        }
        stream.releaseSustain();
        long drained = 0L;
        ByteBuffer buffer;
        while ((buffer = stream.read(ONE_SECOND)).remaining() > 0) {
            drained += buffer.remaining() / FRAME_BYTES;
            assertTrue(drained < 10L * 48_000L, "尾音长度必须有限");
        }
        assertTrue(stream.atEnd(), "跌破 2 m/s 后流必须自己结束");
        assertTrue(stream.position() >= SAMPLE_FRAMES, "游标要走完整段素材，实际 " + stream.position());
    }

    @Test
    void immediateStopRampsToSilenceWithinFiveMilliseconds() throws Exception {
        TrainBrakeSustainAudioStream stream = new ToneStream(SAMPLE_FRAMES).open();
        stream.read(ONE_SECOND);
        stream.stopImmediately();
        long emitted = 0L;
        short last = 1;
        ByteBuffer buffer;
        while ((buffer = stream.read(ONE_SECOND)).remaining() > 0) {
            while (buffer.remaining() >= FRAME_BYTES) {
                last = buffer.getShort();
                emitted++;
            }
        }
        assertEquals(240L, emitted, "5ms @ 48kHz 正好 240 帧");
        assertTrue(stream.atEnd());
        assertTrue(Math.abs(last) < 200, "最后一帧必须已归零，实际 " + last);
    }

    @Test
    void crossfadeLeavesNoStepAtTheWrap() throws Exception {
        TrainBrakeSustainAudioStream stream = new ToneStream(SAMPLE_FRAMES).open();
        short previous = 0;
        int worstDelta = 0;
        // 逐帧走满 3 秒，必然覆盖至少一次窗口尾部回绕
        for (int i = 0; i < 3 * 48_000; i++) {
            ByteBuffer buffer = stream.read(FRAME_BYTES);
            assertEquals(FRAME_BYTES, buffer.remaining());
            short sample = buffer.getShort();
            worstDelta = Math.max(worstDelta, Math.abs(sample - previous));
            previous = sample;
        }
        // 440Hz/48k 正弦自身的相邻差上限约 1150；出现台阶就会远超这个量
        assertTrue(worstDelta < 2000, "回绕处不得出现台阶，实际最大跳变 " + worstDelta);
    }

    @Test
    void silentFallbackEndsImmediately() throws Exception {
        TrainBrakeSustainAudioStream stream = TrainBrakeSustainAudioStream
                .silent(FORMAT, new TrainBrakeSustainAudioStream.Control());
        assertNotNull(stream.getFormat());
        assertEquals(0, stream.read(ONE_SECOND).remaining());
    }

    /** 440Hz 正弦测试素材：避免零值让交叉淡化/台阶断言失去意义。 */
    private static final class ToneStream {

        private final int frames;

        ToneStream(int frames) {
            this.frames = frames;
        }

        TrainBrakeSustainAudioStream open() {
            short[] pcm = new short[this.frames];
            for (int i = 0; i < this.frames; i++) {
                pcm[i] = (short) Math.round(20_000.0D * Math.sin(2.0D * Math.PI * 440.0D * i / 48_000.0D));
            }
            return new TrainBrakeSustainAudioStream(pcm, 1, FORMAT);
        }
    }
}
