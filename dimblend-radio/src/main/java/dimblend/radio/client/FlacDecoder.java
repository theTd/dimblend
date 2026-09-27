package dimblend.radio.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;

import org.jflac.ChannelData;
import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.StreamInfo;

/**
 * FLAC 解码：jFLAC {@link FLACDecoder} 逐帧读，直接取每声道已去相关的整型样本
 * （{@link ChannelData#getOutput()}），按源位宽移位成 16bit 小端交织 PCM，保留全部声道。
 * 下混单声道由 {@link RadioLibrary} 统一做（与 ogg/mp3/wav 同一套 toMono）。
 *
 * <p>不走 javax.sound SPI（{@code FlacAudioFileReader}）：它要求可 mark 的流，且 24bit→16bit
 * 依赖 AudioSystem 转换器，生产 jar 的 SPI 注册也不可靠。也不用 jFLAC 的
 * {@code decodeFrame}/{@code PCMProcessor}：那条只支持 8/16/24bit，且输出字节格式随位宽变（8bit 无符号）。</p>
 */
final class FlacDecoder {
    /** 与 MP3 同上限：解码后 PCM 超过即拒绝，防超长/损坏文件撑爆堆。 */
    private static final long MAX_PCM_BYTES = 256L << 20;

    static RadioLibrary.Pcm decode(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            FLACDecoder decoder = new FLACDecoder(in);
            decoder.readMetadata();
            StreamInfo info = decoder.getStreamInfo();
            if (info == null) {
                throw new IOException("FLAC has no STREAMINFO: " + file);
            }
            int sampleRate = info.getSampleRate();
            int channels = info.getChannels();
            int bitsPerSample = info.getBitsPerSample();
            if (sampleRate <= 0 || channels <= 0 || bitsPerSample < 4 || bitsPerSample > 32) {
                throw new IOException("unsupported FLAC stream (rate=" + sampleRate + ", channels=" + channels
                        + ", bits=" + bitsPerSample + "): " + file);
            }
            int frameBytes = channels * 2;
            long expected = info.getTotalSamples() * frameBytes;
            if (expected > MAX_PCM_BYTES) {
                throw new IOException("decoded FLAC exceeds 256MB, refusing: " + expected + " bytes");
            }
            // STREAMINFO 给了总样本数时一次分配到位；未知（0）则按块扩容
            byte[] data = new byte[(int) Math.max(expected, 1 << 16)];
            int len = 0;
            Frame frame;
            while ((frame = decoder.readNextFrame()) != null) {
                int block = frame.header.blockSize;
                ChannelData[] channelData = decoder.getChannelData();
                int needed = len + block * frameBytes;
                if (needed > MAX_PCM_BYTES) {
                    throw new IOException("decoded FLAC exceeds 256MB, refusing: " + needed + " bytes");
                }
                if (needed > data.length) {
                    data = java.util.Arrays.copyOf(data, (int) Math.min(MAX_PCM_BYTES,
                            Math.max(needed, (long) data.length * 2)));
                }
                for (int s = 0; s < block; s++) {
                    for (int c = 0; c < channels; c++) {
                        int v = to16(channelData[c].getOutput()[s], bitsPerSample);
                        data[len++] = (byte) v;
                        data[len++] = (byte) (v >> 8);
                    }
                }
            }
            if (len == 0) {
                throw new IOException("FLAC contains no decodable frames: " + file);
            }
            byte[] pcm = len == data.length ? data : java.util.Arrays.copyOf(data, len);
            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            return new RadioLibrary.Pcm(format, pcm, (double) len / frameBytes / sampleRate);
        }
    }

    /** 有符号 N bit 样本 → 有符号 16bit：高于 16 截低位，低于 16 左移补零。 */
    static int to16(int sample, int bitsPerSample) {
        if (bitsPerSample > 16) {
            return sample >> (bitsPerSample - 16);
        }
        return sample << (16 - bitsPerSample);
    }

    private FlacDecoder() {
    }
}
