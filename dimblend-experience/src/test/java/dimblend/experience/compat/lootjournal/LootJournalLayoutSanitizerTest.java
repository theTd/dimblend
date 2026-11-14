package dimblend.experience.compat.lootjournal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LootJournalLayoutSanitizer} 的纯逻辑单测：钉死玩家端 {@code TOTA} 错字修复、
 * 合法自定义布局零干预、未知词元隔离与空串兜底契约。
 */
class LootJournalLayoutSanitizerTest {

    @BeforeEach
    void resetWarnDedupe() {
        LootJournalLayoutSanitizer.resetWarningStateForTest();
    }

    @Test
    void playerTruncatedTotaTypoIsRepairedToFullLayoutWithIconPreserved() {
        // 对应玩家端 config/obscuria/loot_journal-client.toml 第 44 行实测原串
        String raw = "COUNT 4px NAME 4px ICON 4px TOTA";
        String upstreamNormalized = upstreamNormalize(raw);

        assertFalse(LootJournalLayoutSanitizer.isUpstreamValid(upstreamNormalized));
        assertEquals("COUNT 4 NAME 4 ICON 4 TOTAL",
                LootJournalLayoutSanitizer.sanitizeNormalized(raw, upstreamNormalized));
        assertEquals("COUNT 4px NAME 4px ICON 4px TOTAL",
                LootJournalLayoutSanitizer.formatForDisplay("COUNT 4 NAME 4 ICON 4 TOTAL"));
    }

    @Test
    void validDefaultAndCustomIconlessLayoutsPassThroughUntouched() {
        String defaultRaw = "COUNT 4px NAME 4px ICON 4px TOTAL";
        String defaultNorm = upstreamNormalize(defaultRaw);
        assertTrue(LootJournalLayoutSanitizer.isUpstreamValid(defaultNorm));
        assertSame(defaultNorm, LootJournalLayoutSanitizer.sanitizeNormalized(defaultRaw, defaultNorm),
                "合法默认配置必须零拷贝原样放行");

        // 用户故意去掉 ICON 的合法自定义配置不得被强塞回 ICON
        String customNoIconRaw = "count+6px+name";
        String customNoIconNorm = upstreamNormalize(customNoIconRaw);
        assertTrue(LootJournalLayoutSanitizer.isUpstreamValid(customNoIconNorm));
        assertSame(customNoIconNorm,
                LootJournalLayoutSanitizer.sanitizeNormalized(customNoIconRaw, customNoIconNorm),
                "合法自定义无图标布局必须原样尊重");
    }

    @Test
    void threeOrMoreLetterUniquePrefixesRepairCleanlyWhileShortAmbiguousFragmentsDrop() {
        assertEquals("ICON 4 NAME 2 COUNT 6 TOTAL",
                LootJournalLayoutSanitizer.repairNormalized("ICO 4px NAM 2PX COU 6px TOT"));
        // 2 字符碎片不猜，直接剔除并折叠孤立间距，保住其余合法元素
        assertEquals("COUNT 4 ICON",
                LootJournalLayoutSanitizer.repairNormalized("COUNT 4px TO 8px ICON"));
    }

    @Test
    void unknownTokensAndLeadingTrailingWhitespaceKeepSurvivingElementsWithoutDanglingGaps() {
        String rawWithWhitespace = "  COUNT 4px NAME 4px ICON  ";
        String normWithWhitespace = upstreamNormalize(rawWithWhitespace);
        // 上游 "".split("\\s+") 对前导空格会产生首个空词元并抛异常
        assertFalse(LootJournalLayoutSanitizer.isUpstreamValid(normWithWhitespace));
        assertEquals("COUNT 4 NAME 4 ICON",
                LootJournalLayoutSanitizer.sanitizeNormalized(rawWithWhitespace, normWithWhitespace));

        // 末尾坏词元：剔除坏词元与悬空 4px，绝不连累前面的 ICON
        assertEquals("COUNT 4 NAME 4 ICON",
                LootJournalLayoutSanitizer.repairNormalized("COUNT 4px NAME 4px ICON 4px UNKNOWN_TOKEN"));
        // 头部坏词元与超大溢出间距：不留前导间距、不留 NumberFormatException 隐患
        assertEquals("ICON 4 NAME",
                LootJournalLayoutSanitizer.repairNormalized("BAD 12px ICON 999999999999999px 4px NAME"));
    }

    @Test
    void emptyNullOrAllInvalidInputFallsBackToFullDefaultInsteadOfNameOnly() {
        assertEquals("COUNT 4 NAME 4 ICON 4 TOTAL", LootJournalLayoutSanitizer.sanitizeNormalized(null, null));
        assertEquals("COUNT 4 NAME 4 ICON 4 TOTAL", LootJournalLayoutSanitizer.repairNormalized(""));
        assertEquals("COUNT 4 NAME 4 ICON 4 TOTAL", LootJournalLayoutSanitizer.repairNormalized("   "));
        assertEquals("COUNT 4 NAME 4 ICON 4 TOTAL", LootJournalLayoutSanitizer.repairNormalized("4px 8px"));
        assertEquals("COUNT 4 NAME 4 ICON 4 TOTAL", LootJournalLayoutSanitizer.repairNormalized("FOO BAR"));
    }

    /** 复刻 {@code LayoutParser#normalize(String)} 字节码（{@code + / - -> ' '}，{@code px -> ""}）。 */
    private static String upstreamNormalize(String raw) {
        return raw.replace("+", " ").replace("-", " ").replace("px", "");
    }
}
