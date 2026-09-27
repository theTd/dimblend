package dimblend.radio.client;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 元数据标题解析：全手造最小字节夹具，不依赖二进制音频文件。
 */
class TrackTitlesTest {
    // ---- 文件名回退 ----

    @Test
    void displayPrefersMetadataOverFileName() {
        assertEquals("夜航星", TrackTitles.displayName("track03.mp3", "夜航星"));
    }

    @Test
    void displayFallsBackToFileNameWithoutExtension() {
        assertEquals("track03", TrackTitles.displayName("track03.mp3", null));
        assertEquals("track03", TrackTitles.displayName("track03.mp3", "   "));
        assertEquals("noext", TrackTitles.displayName("noext", null));
        assertEquals(".ogg", TrackTitles.displayName(".ogg", null));
        assertEquals("a.b.c", TrackTitles.displayName("a.b.c.flac", null));
    }

    @Test
    void cleanTakesFirstLineAndTruncates() {
        assertEquals("abc", TrackTitles.clean("  abc\ndef  "));
        assertNull(TrackTitles.clean("   "));
        assertNull(TrackTitles.clean(null));
        String longTitle = "x".repeat(200);
        assertEquals(128, TrackTitles.clean(longTitle).length());
    }

    @Test
    void formatTimeFloorAndHours() {
        assertEquals("0:00", TrackTitles.formatTime(-3.2));
        assertEquals("0:05", TrackTitles.formatTime(5.9));
        assertEquals("1:23", TrackTitles.formatTime(83.0));
        assertEquals("12:05", TrackTitles.formatTime(725.4));
        assertEquals("1:02:03", TrackTitles.formatTime(3723.0));
    }

    @Test
    void mp3Id3v23Artist() {
        byte[] titleFrame = textFrame("TIT2", (byte) 3, "曲".getBytes(StandardCharsets.UTF_8));
        byte[] artistFrame = textFrame("TPE1", (byte) 3, "某乐队".getBytes(StandardCharsets.UTF_8));
        byte[] file = id3v23(concat(titleFrame, artistFrame));
        assertEquals("曲", TrackTitles.readTitle("a.mp3", file));
        assertEquals("某乐队", TrackTitles.readArtist("a.mp3", file));
    }

    @Test
    void mp3Id3v1Artist() {
        byte[] file = new byte[256];
        int base = file.length - 128;
        file[base] = 'T';
        file[base + 1] = 'A';
        file[base + 2] = 'G';
        byte[] artist = "V1Artist".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(artist, 0, file, base + 33, artist.length);
        assertEquals("V1Artist", TrackTitles.readArtist("a.mp3", file));
    }

    @Test
    void mp3WithoutArtistReturnsNull() {
        byte[] frame = textFrame("TIT2", (byte) 3, "只有标题".getBytes(StandardCharsets.UTF_8));
        assertNull(TrackTitles.readArtist("a.mp3", id3v23(frame)));
    }

    @Test
    void oggArtistAndAuthorFallback() {
        byte[] idPacket = concat(new byte[] {0x01}, "vorbis".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("主唱",
                TrackTitles.readArtist("a.ogg", oggPages(idPacket, vorbisComment("ARTIST=主唱"))));
        assertEquals("写手",
                TrackTitles.readArtist("a.ogg", oggPages(idPacket, vorbisComment("AUTHOR=写手"))));
    }

    @Test
    void flacArtist() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'f', 'L', 'a', 'C'});
        out.writeBytes(new byte[] {0, 0, 0, 34});
        out.writeBytes(new byte[34]);
        byte[] comment = rawVorbisComment("TITLE=T", "ARTIST=海风");
        out.writeBytes(new byte[] {(byte) 0x84, 0, 0, (byte) comment.length});
        out.writeBytes(comment);
        assertEquals("海风", TrackTitles.readArtist("a.flac", out.toByteArray()));
    }

    @Test
    void wavIartArtist() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'});
        byte[] inam = "T".getBytes(StandardCharsets.UTF_8);
        byte[] iart = "码头工人".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        list.writeBytes(new byte[] {'I', 'N', 'F', 'O'});
        list.writeBytes(new byte[] {'I', 'N', 'A', 'M'});
        list.writeBytes(le32(inam.length));
        list.writeBytes(inam);
        if ((inam.length & 1) != 0) {
            list.write(0); // RIFF 奇数块补齐
        }
        list.writeBytes(new byte[] {'I', 'A', 'R', 'T'});
        list.writeBytes(le32(iart.length));
        list.writeBytes(iart);
        if ((iart.length & 1) != 0) {
            list.write(0);
        }
        byte[] listBytes = list.toByteArray();
        out.writeBytes(new byte[] {'L', 'I', 'S', 'T'});
        out.writeBytes(le32(listBytes.length));
        out.writeBytes(listBytes);
        assertEquals("码头工人", TrackTitles.readArtist("a.wav", out.toByteArray()));
    }


    // ---- MP3 ----
    @Test
    void mp3Id3v23Utf8Title() {
        byte[] frame = textFrame("TIT2", (byte) 3, "夜航星".getBytes(StandardCharsets.UTF_8));
        byte[] file = id3v23(frame);
        assertEquals("夜航星", TrackTitles.readTitle("a.mp3", file));
    }

    @Test
    void mp3Id3v23Latin1Title() {
        byte[] frame = textFrame("TIT2", (byte) 0, "Cafe del Mar".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("Cafe del Mar", TrackTitles.readTitle("a.mp3", id3v23(frame)));
    }

    @Test
    void mp3Id3v23Utf16Title() {
        byte[] raw = "晨".getBytes(StandardCharsets.UTF_16LE);
        byte[] body = new byte[2 + raw.length + 2];
        body[0] = (byte) 0xFF;
        body[1] = (byte) 0xFE;
        System.arraycopy(raw, 0, body, 2, raw.length);
        byte[] frame = textFrame("TIT2", (byte) 1, body);
        assertEquals("晨", TrackTitles.readTitle("a.mp3", id3v23(frame)));
    }

    @Test
    void mp3Id3v22TT2Title() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] text = "oldie".getBytes(StandardCharsets.ISO_8859_1);
        out.writeBytes(new byte[] {'T', 'T', '2'});
        out.writeBytes(new byte[] {0, 0, (byte) (text.length + 1)});
        out.write(0);
        out.writeBytes(text);
        byte[] body = out.toByteArray();
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.writeBytes(new byte[] {'I', 'D', '3', 2, 0, 0});
        tag.writeBytes(syncsafe(body.length));
        tag.writeBytes(body);
        assertEquals("oldie", TrackTitles.readTitle("a.mp3", tag.toByteArray()));
    }

    @Test
    void mp3Id3v1Fallback() {
        byte[] file = new byte[256];
        int base = file.length - 128;
        file[base] = 'T';
        file[base + 1] = 'A';
        file[base + 2] = 'G';
        byte[] title = "V1Title".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(title, 0, file, base + 3, title.length);
        assertEquals("V1Title", TrackTitles.readTitle("a.mp3", file));
    }

    @Test
    void mp3WithoutTagsReturnsNull() {
        assertNull(TrackTitles.readTitle("a.mp3", new byte[512]));
    }

    // ---- OGG ----

    @Test
    void oggVorbisCommentTitle() {
        byte[] idPacket = concat(new byte[] {0x01}, "vorbis".getBytes(StandardCharsets.ISO_8859_1));
        byte[] commentPacket = vorbisComment("TITLE=星空电台", "ARTIST=某人");
        byte[] file = oggPages(idPacket, commentPacket);
        assertEquals("星空电台", TrackTitles.readTitle("a.ogg", file));
    }

    @Test
    void oggTitleKeyCaseInsensitive() {
        byte[] idPacket = concat(new byte[] {0x01}, "vorbis".getBytes(StandardCharsets.ISO_8859_1));
        byte[] commentPacket = vorbisComment("title=小写也认");
        assertEquals("小写也认", TrackTitles.readTitle("a.ogg", oggPages(idPacket, commentPacket)));
    }

    @Test
    void oggMultiSegmentCommentTitle() {
        // vendor 300 字节 → comment 包跨段（2 段），组包必须拼接
        byte[] idPacket = concat(new byte[] {0x01}, "vorbis".getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream vc = new ByteArrayOutputStream();
        byte[] vendor = "v".repeat(300).getBytes(StandardCharsets.UTF_8);
        vc.writeBytes(le32(vendor.length));
        vc.writeBytes(vendor);
        byte[] title = "TITLE=跨段标题".getBytes(StandardCharsets.UTF_8);
        vc.writeBytes(le32(1));
        vc.writeBytes(le32(title.length));
        vc.writeBytes(title);
        byte[] commentPacket = concat(new byte[] {0x03},
                "vorbis".getBytes(StandardCharsets.ISO_8859_1), vc.toByteArray());
        assertEquals("跨段标题",
                TrackTitles.readTitle("a.ogg", oggPages(idPacket, commentPacket)));
    }


    @Test
    void oggWithoutCommentReturnsNull() {
        assertNull(TrackTitles.readTitle("a.ogg", new byte[512]));
    }

    // ---- FLAC ----

    @Test
    void flacVorbisCommentTitle() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'f', 'L', 'a', 'C'});
        out.writeBytes(new byte[] {0, 0, 0, 34}); // STREAMINFO
        out.writeBytes(new byte[34]);
        byte[] comment = rawVorbisComment("TITLE=深海回声");
        out.writeBytes(new byte[] {(byte) 0x84, 0, 0, (byte) comment.length}); // last + type 4
        out.writeBytes(comment);
        assertEquals("深海回声", TrackTitles.readTitle("a.flac", out.toByteArray()));
    }

    @Test
    void flacWithoutCommentReturnsNull() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'f', 'L', 'a', 'C'});
        out.writeBytes(new byte[] {(byte) 0x80, 0, 0, 34});
        out.writeBytes(new byte[34]);
        assertNull(TrackTitles.readTitle("a.flac", out.toByteArray()));
    }

    // ---- WAV ----

    @Test
    void wavInfoInamTitle() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'});
        byte[] inam = "湖畔".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        list.writeBytes(new byte[] {'I', 'N', 'F', 'O'});
        list.writeBytes(new byte[] {'I', 'N', 'A', 'M'});
        list.writeBytes(le32(inam.length));
        list.writeBytes(inam);
        if ((inam.length & 1) != 0) {
            list.write(0);
        }
        byte[] listBytes = list.toByteArray();
        out.writeBytes(new byte[] {'L', 'I', 'S', 'T'});
        out.writeBytes(le32(listBytes.length));
        out.writeBytes(listBytes);
        assertEquals("湖畔", TrackTitles.readTitle("a.wav", out.toByteArray()));
    }

    @Test
    void wavEmbeddedId3Title() {
        byte[] frame = textFrame("TIT2", (byte) 3, "内嵌ID3".getBytes(StandardCharsets.UTF_8));
        byte[] tag = id3v23(frame);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'});
        out.writeBytes(new byte[] {'i', 'd', '3', ' '});
        out.writeBytes(le32(tag.length));
        out.writeBytes(tag);
        if ((tag.length & 1) != 0) {
            out.write(0);
        }
        assertEquals("内嵌ID3", TrackTitles.readTitle("a.wav", out.toByteArray()));
    }

    @Test
    void wavWithoutListReturnsNull() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'});
        out.writeBytes(new byte[] {'d', 'a', 't', 'a'});
        out.writeBytes(le32(4));
        out.writeBytes(new byte[4]);
        assertNull(TrackTitles.readTitle("a.wav", out.toByteArray()));
    }

    // ---- 夹具构造 ----

    private static byte[] textFrame(String id, byte enc, byte[] text) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        int size = text.length + 1;
        frame.writeBytes(new byte[] {(byte) (size >> 24), (byte) (size >> 16), (byte) (size >> 8),
                (byte) size});
        frame.writeBytes(new byte[] {0, 0}); // flags
        frame.write(enc);
        frame.writeBytes(text);
        return frame.toByteArray();
    }

    private static byte[] id3v23(byte[] frame) {
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.writeBytes(new byte[] {'I', 'D', '3', 3, 0, 0});
        tag.writeBytes(syncsafe(frame.length));
        tag.writeBytes(frame);
        return tag.toByteArray();
    }

    private static byte[] syncsafe(int v) {
        return new byte[] {(byte) ((v >> 21) & 0x7F), (byte) ((v >> 14) & 0x7F),
                (byte) ((v >> 7) & 0x7F), (byte) (v & 0x7F)};
    }

    private static byte[] le32(int v) {
        return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] rawVorbisComment(String... comments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = "test".getBytes(StandardCharsets.UTF_8);
        out.writeBytes(le32(vendor.length));
        out.writeBytes(vendor);
        out.writeBytes(le32(comments.length));
        for (String c : comments) {
            byte[] b = c.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(le32(b.length));
            out.writeBytes(b);
        }
        return out.toByteArray();
    }

    private static byte[] vorbisComment(String... comments) {
        return concat(new byte[] {0x03}, "vorbis".getBytes(StandardCharsets.ISO_8859_1),
                rawVorbisComment(comments));
    }

    private static byte[] oggPages(byte[] packet1, byte[] packet2) {
        // 两包都塞进单页段表（每段 ≤255，切段即可），comment 解析只看包序号
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        for (byte[] p : new byte[][] {packet1, packet2}) {
            int off = 0;
            while (off < p.length) {
                int n = Math.min(255, p.length - off);
                seg.write(n); // 段表项是段长度单字节，不是包数据
                off += n;
            }
            // 包尾段 <255 即包结束；整除 255 时补 0 长段
            if (p.length % 255 == 0) {
                seg.write(0);
            }
        }
        byte[] segTable = seg.toByteArray();
        // 重组包数据
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(packet1);
        data.writeBytes(packet2);
        page.writeBytes(new byte[] {'O', 'g', 'g', 'S', 0, 0});
        page.writeBytes(new byte[8]); // granule
        page.writeBytes(new byte[] {1, 0, 0, 0}); // serial
        page.writeBytes(new byte[4]); // seq
        page.writeBytes(new byte[4]); // crc（不校验，置零）
        page.write(segTable.length);
        page.writeBytes(segTable);
        page.writeBytes(data.toByteArray());
        return page.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
