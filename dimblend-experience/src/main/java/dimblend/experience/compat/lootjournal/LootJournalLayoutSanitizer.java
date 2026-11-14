package dimblend.experience.compat.lootjournal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loot Journal {@code elementOrder} 布局字符串的容错与自动修复（纯逻辑，零三方类型依赖）。
 *
 * <h2>上游缺陷（字节码取证：{@code loot_journal-neoforge-1.21.1-6.2.2.jar}）</h2>
 * <ol>
 *   <li>{@code LayoutParser#parse(String)} 先调 {@code normalize(String)}（将 {@code +}、{@code -}
 *       换成空格并去掉小写 {@code px}），再 {@code split("\\s+")} 逐词解析：全数字走
 *       {@code GapToken(Integer.parseInt(token))}，其余走 {@code createElement(token)}（仅接受
 *       {@code ICON} / {@code NAME} / {@code COUNT} / {@code TOTAL}）。</li>
 *   <li>一旦任一词元非法（如玩家 {@code config/obscuria/loot_journal-client.toml} 第 44 行写成了
 *       {@code "COUNT 4px NAME 4px ICON 4px TOTA"}，末尾漏了一个 {@code L}，或带前导空格产生空词元），
 *       {@code createElement} 抛 {@code IllegalArgumentException}。</li>
 *   <li>{@code ConfigCache#refresh()}（字节码 {@code 70..108}）捕获 {@code Exception} 后赋值为
 *       {@code PickupLayout.DEFAULT}；而 {@code PickupLayout.<clinit>}（字节码 {@code 0..16}）把
 *       {@code DEFAULT} 定义成了 {@code new PickupLayout(List.of(NameToken.SHARED))}——<b>只有物品名
 *       {@code NAME}，完全不含 {@code ICON}、{@code COUNT}、{@code TOTAL}</b>，导致拾取 HUD 面板与文字仍在、
 *       物品图标神秘消失。</li>
 * </ol>
 *
 * <h2>容错契约</h2>
 * <ul>
 *   <li><b>合法配置零干预</b>：凡能被上游 {@code LayoutParser#parse} 无异常解析的字符串（包括用户刻意不写
 *       {@code ICON} 的自定义布局如 {@code "COUNT 4px NAME"}），原样返回上游 {@code normalize} 结果，不打日志。</li>
 *   <li><b>前缀错字修复</b>：对长度 {@code >= 3} 且唯一匹配某个合法元素前缀的词元（如 {@code TOTA}/{@code TOT}
 *       -&gt; {@code TOTAL}、{@code COUN}/{@code COU} -&gt; {@code COUNT}、{@code ICO} -&gt; {@code ICON}、
 *       {@code NAM} -&gt; {@code NAME}），自动补全为对应标准词元。</li>
 *   <li><b>未知词元剔除与间距收敛</b>：无法识别的词元直接丢弃，并自动折掉前导、尾随或连续的孤立间距词元，
 *       保留其余已写出的合法元素（如 {@code ICON}）。</li>
 *   <li><b>全空兜底</b>：若清洗后没有任何元素词元剩余，回退到配置默认值对应的
 *       {@code "COUNT 4 NAME 4 ICON 4 TOTAL"}（绝不回退到只剩 {@code NAME} 的 {@code PickupLayout.DEFAULT}）。</li>
 * </ul>
 */
public final class LootJournalLayoutSanitizer {

    /** 对应 {@code Config.ELEMENT_ORDER} 默认值 {@code "COUNT 4px NAME 4px ICON 4px TOTAL"} 经 {@code normalize} 后的形式。 */
    static final String DEFAULT_NORMALIZED = "COUNT 4 NAME 4 ICON 4 TOTAL";

    private static final List<String> CANONICAL_ELEMENTS = List.of("ICON", "NAME", "COUNT", "TOTAL");
    private static final int MIN_PREFIX_REPAIR_LENGTH = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger("dimblend_experience/loot-journal-layout");
    private static final AtomicReference<String> LAST_WARNED_RAW = new AtomicReference<>();

    /**
     * 供 {@code LayoutParser#normalize} 钩子调用：上游结果已合法时原样放行，否则修复并去重记录一次告警。
     *
     * @param raw                原始 {@code elementOrder} 字符串
     * @param upstreamNormalized 上游 {@code LayoutParser#normalize(raw)} 的返回值（{@code raw == null} 时为 {@code null}）
     * @return 保证能被 {@code LayoutParser#parse} 无异常解析的归一化字符串
     */
    public static String sanitizeNormalized(String raw, String upstreamNormalized) {
        if (raw != null && isUpstreamValid(upstreamNormalized)) {
            LAST_WARNED_RAW.set(null);
            return upstreamNormalized;
        }
        String repaired = repairNormalized(upstreamNormalized != null ? upstreamNormalized : raw);
        String warnKey = String.valueOf(raw);
        if (!warnKey.equals(LAST_WARNED_RAW.getAndSet(warnKey))) {
            LOGGER.warn("[loot-journal-layout] elementOrder \"{}\" 含非法词元"
                    + "（Loot Journal 原生回退 PickupLayout.DEFAULT 仅保留 NAME 会导致物品图标消失），"
                    + "已自动修正为 \"{}\"", raw, formatForDisplay(repaired));
        }
        return repaired;
    }

    /**
     * 判断归一化字符串是否已能被上游 {@code LayoutParser#parse} 完整无异常解析。
     */
    static boolean isUpstreamValid(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        String[] parts = normalized.split("\\s+");
        if (parts.length == 0) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            if (isAllAsciiDigits(part)) {
                if (!isValidNonNegativeInt(part)) {
                    return false;
                }
            } else if (!CANONICAL_ELEMENTS.contains(part.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将任意损坏或含错字的布局字符串修复为合法的归一化布局字符串（纯函数，不写日志）。
     */
    public static String repairNormalized(String input) {
        if (input == null) {
            return DEFAULT_NORMALIZED;
        }
        String cleaned = input
                .replace('+', ' ')
                .replace('-', ' ')
                .replaceAll("(?i)px", "")
                .trim();
        if (cleaned.isEmpty()) {
            return DEFAULT_NORMALIZED;
        }

        String[] parts = cleaned.split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        boolean hasElement = false;
        String pendingGap = null;

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (isAllAsciiDigits(part)) {
                if (hasElement && pendingGap == null && isValidNonNegativeInt(part)) {
                    pendingGap = part;
                }
                continue;
            }
            String element = resolveElementToken(part);
            if (element != null) {
                if (hasElement && pendingGap != null) {
                    out.add(pendingGap);
                }
                pendingGap = null;
                out.add(element);
                hasElement = true;
            }
        }

        if (!hasElement) {
            return DEFAULT_NORMALIZED;
        }
        return String.join(" ", out);
    }

    static String resolveElementToken(String token) {
        String upper = token.toUpperCase(Locale.ROOT);
        if (CANONICAL_ELEMENTS.contains(upper)) {
            return upper;
        }
        if (upper.length() < MIN_PREFIX_REPAIR_LENGTH) {
            return null;
        }
        String uniqueMatch = null;
        for (String candidate : CANONICAL_ELEMENTS) {
            if (candidate.startsWith(upper)) {
                if (uniqueMatch != null) {
                    return null;
                }
                uniqueMatch = candidate;
            }
        }
        return uniqueMatch;
    }

    static String formatForDisplay(String normalized) {
        String[] parts = normalized.split("\\s+");
        StringBuilder sb = new StringBuilder(normalized.length() + 8);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String part = parts[i];
            sb.append(part);
            if (isAllAsciiDigits(part)) {
                sb.append("px");
            }
        }
        return sb.toString();
    }

    static void resetWarningStateForTest() {
        LAST_WARNED_RAW.set(null);
    }

    private static boolean isAllAsciiDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidNonNegativeInt(String digits) {
        try {
            return Integer.parseInt(digits) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private LootJournalLayoutSanitizer() {
    }
}
