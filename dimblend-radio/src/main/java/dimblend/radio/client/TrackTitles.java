package dimblend.radio.client;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 音频元数据标题/作者：metadata 优先，取不到回退文件名（去扩展名）。
 *
 * <p>只读容器头/标签区，不解码 PCM；全部操作在调用方给定的字节数组上，无 IO。
 * 任一解析失败返回 {@code null}（调用方回退），永不抛受检异常——损坏的标签
 * 不能把整曲扫库搞崩（见 {@code RadioLibrary.scanNow} 的逐文件 try/catch）。</p>
 *
 * <ul>
 *   <li>MP3：ID3v2（TIT2/TT2 标题、TPE1/TP1 作者，含 unsynchronisation 还原）→ ID3v1 尾标签。</li>
 *   <li>OGG：页组包找 Vorbis comment（0x03+vorbis）/ OpusTags，取 TITLE=/ARTIST=（AUTHOR 兜底）。</li>
 *   <li>FLAC：metadata 块链找 VORBIS_COMMENT（type 4），同上。</li>
 *   <li>WAV：RIFF 块链找 LIST/INFO/INAM（标题）/IART（作者）；内嵌 {@code id3 } 块走 ID3v2 解析。</li>
 * </ul>
 */
public final class TrackTitles {
    /** tooltip/actionbar 单行上限：超长标题截断加省略号。 */
    public static final int MAX_TITLE_LEN = 128;

    private static final int SCAN_BYTES_CAP = 4 << 20;

    /**
     * @return 清洗后的标题；无标签/解析失败返回 {@code null}
     */
    public static String readTitle(String fileName, byte[] bytes) {
        String[] tag = readTag(fileName, bytes);
        return tag == null ? null : tag[0];
    }

    /**
     * @return 清洗后的作者；无标签/解析失败返回 {@code null}
     */
    public static String readArtist(String fileName, byte[] bytes) {
        String[] tag = readTag(fileName, bytes);
        return tag == null ? null : tag[1];
    }

    /** @return {标题, 作者}（元素可空）；无标签/解析失败返回 {@code null} */
    static String[] readTag(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            return null;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        try {
            if (lower.endsWith(".mp3")) {
                String[] t = readId3v2Tag(bytes, 0, Math.min(bytes.length, SCAN_BYTES_CAP));
                if (t != null) {
                    return t;
                }
                return new String[] {readId3v1Title(bytes), readId3v1Artist(bytes)};
            } else if (lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus")) {
                return readOggTag(bytes);
            } else if (lower.endsWith(".flac")) {
                return readFlacTag(bytes);
            } else if (lower.endsWith(".wav")) {
                return readWavTag(bytes);
            }
        } catch (RuntimeException ignored) {
            // 防御：任何越界/格式怪癖都回退文件名，不崩扫库
        }
        return null;
    }

    /**
     * 显示名：元数据标题优先，否则文件名去扩展名（永不返回扩展名）。
     *
     * @param fileName 含扩展名的文件名（非空）
     * @param title 元数据标题（可空）
     */
    public static String displayName(String fileName, String title) {
        String cleaned = clean(title);
        if (cleaned != null) {
            return cleaned;
        }
        return stripExtension(fileName);
    }

    /** 去扩展名：无点/点在首位（.ogg 这类隐藏名）则原样返回。 */
    public static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    /** trim + 判空 + 截断；无效返回 null。 */
    public static String clean(String title) {
        if (title == null) {
            return null;
        }
        String t = title.trim();
        // 内嵌换行（个别标签多行写）只取首行，tooltip/actionbar 都是单行
        int nl = t.indexOf('\n');
        if (nl >= 0) {
            t = t.substring(0, nl).trim();
        }
        int cr = t.indexOf('\r');
        if (cr >= 0) {
            t = t.substring(0, cr).trim();
        }
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > MAX_TITLE_LEN) {
            return t.substring(0, MAX_TITLE_LEN - 1) + "…";
        }
        return t;
    }

    /**
     * 进度时刻：秒 → {@code m:ss}（≥1h 时 {@code h:mm:ss}），向下取整、负数按 0。
     * 纯函数，供护目镜进度行与单测。
     */
    public static String formatTime(double seconds) {
        long total = Math.max(0L, (long) Math.floor(seconds));
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return h + ":" + String.format("%02d:%02d", m, s);
        }
        return m + ":" + String.format("%02d", s);
    }

    // ---- MP3：ID3v2 ----

    /**
     * @return {标题, 作者}（元素可空）；无标签返回 {@code null}
     */
    static String[] readId3v2Tag(byte[] bytes, int offset, int end) {
        if (end - offset < 10 || bytes[offset] != 'I' || bytes[offset + 1] != 'D'
                || bytes[offset + 2] != '3') {
            return null;
        }
        int major = bytes[offset + 3] & 0xFF;
        int flags = bytes[offset + 5] & 0xFF;
        int size = syncsafe(bytes, offset + 6);
        if (size <= 0) {
            return null;
        }
        int bodyStart = offset + 10;
        int bodyEnd = Math.min(end, bodyStart + size);
        if (bodyEnd - bodyStart < 10) {
            return null;
        }
        byte[] body = bytes;
        int base = bodyStart;
        if ((flags & 0x80) != 0) {
            // unsynchronisation：FF 00 → FF 还原后再按帧解析
            body = removeUnsync(bytes, bodyStart, bodyEnd);
            base = 0;
            bodyEnd = body.length;
        } else if (offset != 0 || end != bytes.length) {
            body = java.util.Arrays.copyOfRange(bytes, bodyStart, bodyEnd);
            base = 0;
            bodyEnd = body.length;
        }
        int pos = base;
        if ((flags & 0x40) != 0) {
            // extended header：v2.3 是 BE32（含自身 4 字节），v2.4 是 syncsafe
            if (pos + 4 > bodyEnd) {
                return null;
            }
            int ext;
            if (major == 4) {
                ext = syncsafe(body, pos);
                pos += 4 + ext;
            } else {
                ext = be32(body, pos);
                pos += 4 + ext;
            }
        }
        boolean v22 = major == 2;
        String title = null;
        String artist = null;
        while (pos + (v22 ? 6 : 10) <= bodyEnd) {
            String id;
            int frameSize;
            if (v22) {
                if (body[pos] == 0) {
                    break; // 填充区
                }
                id = new String(body, pos, 3, StandardCharsets.ISO_8859_1);
                frameSize = ((body[pos + 3] & 0xFF) << 16) | ((body[pos + 4] & 0xFF) << 8)
                        | (body[pos + 5] & 0xFF);
                pos += 6;
            } else {
                if (body[pos] == 0) {
                    break;
                }
                id = new String(body, pos, 4, StandardCharsets.ISO_8859_1);
                frameSize = major == 4 ? syncsafe(body, pos + 4) : be32(body, pos + 4);
                pos += 10;
            }
            if (frameSize <= 0 || pos + frameSize > bodyEnd) {
                break;
            }
            if (isTitleFrame(id)) {
                if (title == null) {
                    title = clean(decodeTextFrame(body, pos, frameSize));
                }
            } else if (isArtistFrame(id)) {
                if (artist == null) {
                    artist = clean(decodeTextFrame(body, pos, frameSize));
                }
            }
            pos += frameSize;
            if (title != null && artist != null) {
                break; // 双项齐了，后面只剩封面等大帧，不必再扫
            }
        }
        if (title == null && artist == null) {
            return null;
        }
        return new String[] {title, artist};
    }

    private static boolean isTitleFrame(String id) {
        return id.equals("TIT2") || id.equals("TT2");
    }

    private static boolean isArtistFrame(String id) {
        return id.equals("TPE1") || id.equals("TP1");
    }

    /** 文本帧：编码字节 + 字符串（多值只取第一个）。 */
    static String decodeTextFrame(byte[] body, int pos, int size) {
        if (size < 1) {
            return null;
        }
        int enc = body[pos] & 0xFF;
        int start = pos + 1;
        int len = size - 1;
        try {
            switch (enc) {
                case 0:
                    return new String(body, start, nulTerminated(body, start, len, 1),
                            StandardCharsets.ISO_8859_1);
                case 3:
                    return new String(body, start, nulTerminated(body, start, len, 1),
                            StandardCharsets.UTF_8);
                case 1: {
                    // UTF-16 with BOM；无 BOM 按 LE（Windows 工具常见写法）
                    if (len >= 2 && (body[start] & 0xFF) == 0xFF && (body[start + 1] & 0xFF) == 0xFE) {
                        return new String(body, start + 2, nulTerminated(body, start + 2, len - 2, 2),
                                StandardCharsets.UTF_16LE);
                    } else if (len >= 2 && (body[start] & 0xFF) == 0xFE
                            && (body[start + 1] & 0xFF) == 0xFF) {
                        return new String(body, start + 2, nulTerminated(body, start + 2, len - 2, 2),
                                StandardCharsets.UTF_16BE);
                    }
                    return new String(body, start, nulTerminated(body, start, len, 2),
                            StandardCharsets.UTF_16LE);
                }
                case 2:
                    return new String(body, start, nulTerminated(body, start, len, 2),
                            StandardCharsets.UTF_16BE);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 第一个 NUL（按 unit 宽度）之前的长度。 */
    private static int nulTerminated(byte[] body, int start, int len, int unit) {
        int max = Math.min(len, body.length - start);
        if (max <= 0) {
            return 0;
        }
        for (int i = 0; i + unit <= max; i += unit) {
            boolean nul = true;
            for (int u = 0; u < unit; u++) {
                if (body[start + i + u] != 0) {
                    nul = false;
                    break;
                }
            }
            if (nul) {
                return i;
            }
        }
        return max;
    }

    private static byte[] removeUnsync(byte[] bytes, int start, int end) {
        byte[] tmp = new byte[end - start];
        int w = 0;
        for (int r = start; r < end; r++) {
            tmp[w++] = bytes[r];
            if ((bytes[r] & 0xFF) == 0xFF && r + 1 < end && bytes[r + 1] == 0) {
                r++; // 跳过填充 00
            }
        }
        return java.util.Arrays.copyOf(tmp, w);
    }

    static String readId3v1Title(byte[] bytes) {
        return readId3v1Field(bytes, 3, 30);
    }

    static String readId3v1Artist(byte[] bytes) {
        return readId3v1Field(bytes, 33, 30);
    }

    /** ID3v1 尾 128 字节 "TAG" 内字段：Latin-1，NUL/空格结尾都 trim 掉。 */
    private static String readId3v1Field(byte[] bytes, int fieldOff, int fieldLen) {
        int n = bytes.length;
        if (n < 128 || bytes[n - 128] != 'T' || bytes[n - 127] != 'A' || bytes[n - 126] != 'G') {
            return null;
        }
        // 30 字节 Latin-1，NUL/空格结尾都 trim 掉
        int len = fieldLen;
        while (len > 0 && (bytes[n - 128 + fieldOff + len - 1] == 0
                || bytes[n - 128 + fieldOff + len - 1] == ' ')) {
            len--;
        }
        if (len <= 0) {
            return null;
        }
        return clean(new String(bytes, n - 128 + fieldOff, len, StandardCharsets.ISO_8859_1));
    }

    // ---- OGG：Vorbis comment / OpusTags ----

    static String[] readOggTag(byte[] bytes) {
        int len = Math.min(bytes.length, SCAN_BYTES_CAP);
        int pos = 0;
        // 跨页组包：未完成的包暂存（comment 包是第 2 包，通常单页内；跨页也兜底）
        java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
        int completedPackets = 0;
        int pages = 0;
        while (pos + 27 <= len && pages < 64) {
            if (bytes[pos] != 'O' || bytes[pos + 1] != 'g' || bytes[pos + 2] != 'g'
                    || bytes[pos + 3] != 'S') {
                break;
            }
            int segments = bytes[pos + 26] & 0xFF;
            if (pos + 27 + segments > len) {
                break;
            }
            int dataStart = pos + 27 + segments;
            int dataPos = dataStart;
            for (int s = 0; s < segments; s++) {
                int segLen = bytes[pos + 27 + s] & 0xFF;
                if (dataPos + segLen > len) {
                    return null;
                }
                pending.write(bytes, dataPos, segLen);
                dataPos += segLen;
                if (segLen < 255) {
                    // 包完整
                    byte[] packet = pending.toByteArray();
                    pending.reset();
                    if (completedPackets == 1) {
                        // 第 2 包即 comment 包（第 1 包是 identification）
                        return pickTag(parseVorbisCommentPacket(packet));
                        // 第 2 包不是 comment 也直接返回（后面不会再有标题）：null 即双空
                    }
                    completedPackets++;
                    if (completedPackets > 2) {
                        return null;
                    }
                }
            }
            // 段表全 255 且包未完：跨页继续（pending 保留）
            int pageLen = dataPos - pos;
            if (pageLen <= 0) {
                break;
            }
            pos = dataPos;
            pages++;
        }
        return null;
    }

    /**
     * @return {标题, 作者}（元素可空）；包不是 comment 包返回 {@code null}
     */
    static String[] pickTag(java.util.List<String> comments) {
        if (comments == null) {
            return null;
        }
        String title = commentValue(comments, "TITLE");
        String artist = commentValue(comments, "ARTIST", "AUTHOR");
        if (title == null && artist == null) {
            return null;
        }
        return new String[] {title, artist};
    }

    /** 按 key 优先级取第一个非空值（key 大小写不敏感）。 */
    static String commentValue(java.util.List<String> comments, String... keys) {
        for (String want : keys) {
            for (String comment : comments) {
                int eq = comment.indexOf('=');
                if (eq > 0 && comment.substring(0, eq).equalsIgnoreCase(want)) {
                    String v = clean(comment.substring(eq + 1));
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    /** Vorbis comment 包（0x03+"vorbis"）或 OpusTags（"OpusTags"）：取原始 KEY=value 列表。 */
    static java.util.List<String> parseVorbisCommentPacket(byte[] packet) {
        int pos;
        if (packet.length > 7 && (packet[0] & 0xFF) == 0x03 && packet[1] == 'v' && packet[2] == 'o') {
            pos = 7;
        } else if (packet.length > 8 && packet[0] == 'O' && packet[1] == 'p' && packet[2] == 'u'
                && packet[3] == 's') {
            pos = 8; // "OpusTags"
        } else {
            return null;
        }
        return readCommentList(packet, pos);
    }

    static java.util.List<String> readCommentList(byte[] buf, int pos) {
        if (pos + 4 > buf.length) {
            return null;
        }
        int vendorLen = le32(buf, pos);
        pos += 4;
        if (vendorLen < 0 || pos + vendorLen > buf.length) {
            return null;
        }
        pos += vendorLen;
        if (pos + 4 > buf.length) {
            return null;
        }
        int count = le32(buf, pos);
        pos += 4;
        if (count < 0 || count > 512) {
            return null;
        }
        java.util.List<String> out = new java.util.ArrayList<>(Math.min(count, 32));
        for (int i = 0; i < count; i++) {
            if (pos + 4 > buf.length) {
                break;
            }
            int commentLen = le32(buf, pos);
            pos += 4;
            if (commentLen < 0 || commentLen > (1 << 20) || pos + commentLen > buf.length) {
                break;
            }
            out.add(new String(buf, pos, commentLen, StandardCharsets.UTF_8));
            pos += commentLen;
        }
        return out;
    }

    // ---- FLAC：metadata 块链 ----

    static String[] readFlacTag(byte[] bytes) {
        int len = Math.min(bytes.length, SCAN_BYTES_CAP);
        if (len < 4 || bytes[0] != 'f' || bytes[1] != 'L' || bytes[2] != 'a' || bytes[3] != 'C') {
            return null;
        }
        int pos = 4;
        for (int blocks = 0; blocks < 32 && pos + 4 <= len; blocks++) {
            boolean last = (bytes[pos] & 0x80) != 0;
            int type = bytes[pos] & 0x7F;
            int size = ((bytes[pos + 1] & 0xFF) << 16) | ((bytes[pos + 2] & 0xFF) << 8)
                    | (bytes[pos + 3] & 0xFF);
            pos += 4;
            if (size < 0 || pos + size > len) {
                return null;
            }
            if (type == 4) {
                return pickTag(readCommentList(bytes, pos));
            }
            pos += size;
            if (last) {
                break;
            }
        }
        return null;
    }

    // ---- WAV：RIFF 块链 ----

    static String[] readWavTag(byte[] bytes) {
        int len = Math.min(bytes.length, SCAN_BYTES_CAP);
        if (len < 12 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F') {
            return readId3v2Tag(bytes, 0, len); // 非 RIFF（如裸 ID3）兜底
        }
        boolean wave = bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
        boolean rf64 = bytes[8] == 'R' && bytes[9] == 'F' && bytes[10] == '6' && bytes[11] == '4';
        if (!wave && !rf64) {
            return null;
        }
        String title = null;
        String artist = null;
        int pos = 12;
        while (pos + 8 <= len) {
            int size = le32(bytes, pos + 4);
            if (size < 0 || size > (1 << 26)) {
                break;
            }
            int dataStart = pos + 8;
            int dataEnd = Math.min(len, dataStart + size);
            if (bytes[pos] == 'L' && bytes[pos + 1] == 'I' && bytes[pos + 2] == 'S'
                    && bytes[pos + 3] == 'T') {
                if (title == null) {
                    title = readInfoValue(bytes, dataStart, dataEnd, "INAM");
                }
                if (artist == null) {
                    artist = readInfoValue(bytes, dataStart, dataEnd, "IART");
                }
            } else if (bytes[pos] == 'i' && bytes[pos + 1] == 'd' && bytes[pos + 2] == '3'
                    && bytes[pos + 3] == ' ') {
                String[] t = readId3v2Tag(bytes, dataStart, dataEnd);
                if (t != null) {
                    if (title == null) {
                        title = t[0];
                    }
                    if (artist == null) {
                        artist = t[1];
                    }
                }
            }
            if (title != null && artist != null) {
                break;
            }
            pos = dataEnd + (size & 1); // 奇数块补齐一字节
        }
        if (title == null && artist == null) {
            return null;
        }
        return new String[] {title, artist};
    }

    /** LIST 块内：form "INFO" → 指定 id 的子块（如 INAM 标题 / IART 作者）。 */
    static String readInfoValue(byte[] bytes, int start, int end, String wantId) {
        if (start + 4 > end || wantId.length() != 4) {
            return null;
        }
        // form 可能是 INFO/JUNK 等：只在 INFO 里找
        boolean info = bytes[start] == 'I' && bytes[start + 1] == 'N' && bytes[start + 2] == 'F'
                && bytes[start + 3] == 'O';
        int pos = start + 4;
        while (pos + 8 <= end) {
            int size = le32(bytes, pos + 4);
            if (size < 0 || size > (1 << 20)) {
                return null;
            }
            int dataStart = pos + 8;
            int dataEnd = Math.min(end, dataStart + size);
            if (info && bytes[pos] == wantId.charAt(0) && bytes[pos + 1] == wantId.charAt(1)
                    && bytes[pos + 2] == wantId.charAt(2) && bytes[pos + 3] == wantId.charAt(3)) {
                return clean(decodeAnsi(bytes, dataStart, dataEnd));
            }
            pos = dataEnd + (size & 1);
        }
        return null;
    }

    /** INFO 子块多为系统 ANSI：严格 UTF-8 失败回退 Latin-1（单字节 1:1，不丢字符）。 */
    static String decodeAnsi(byte[] bytes, int start, int end) {
        int n = end - start;
        while (n > 0 && (bytes[start + n - 1] == 0 || bytes[start + n - 1] == ' ')) {
            n--;
        }
        if (n <= 0) {
            return null;
        }
        String utf = new String(bytes, start, n, StandardCharsets.UTF_8);
        if (!utf.contains("�")) {
            return utf;
        }
        return new String(bytes, start, n, StandardCharsets.ISO_8859_1);
    }

    // ---- 小端/大端整数 ----

    private static int le32(byte[] b, int pos) {
        return (b[pos] & 0xFF) | ((b[pos + 1] & 0xFF) << 8) | ((b[pos + 2] & 0xFF) << 16)
                | ((b[pos + 3] & 0xFF) << 24);
    }

    private static int be32(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8)
                | (b[pos + 3] & 0xFF);
    }

    private static int syncsafe(byte[] b, int pos) {
        return ((b[pos] & 0x7F) << 21) | ((b[pos + 1] & 0x7F) << 14) | ((b[pos + 2] & 0x7F) << 7)
                | (b[pos + 3] & 0x7F);
    }

    private TrackTitles() {
    }
}
