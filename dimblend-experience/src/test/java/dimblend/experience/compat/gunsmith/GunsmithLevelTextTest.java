package dimblend.experience.compat.gunsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TaCZ 原生 tooltip「等级」行的文本口径（{@link GunsmithLevelText}）单元测试。
 *
 * <p>用户口径：{@code 0} 与 {@code 49} 都不得显示 {@code MAX}，只有 {@code 50} 显示 {@code 50 (MAX)}；
 * 早年 TaCZ 那句恒定的 {@code "0 (MAX)"} 就是本缺陷的现象（其等级 API 全是返回 0 的桩）。</p>
 */
class GunsmithLevelTextTest {

    @Test
    void onlyLevelFiftyRendersTheMaxMarker() {
        assertEquals("50 (MAX)", GunsmithLevelText.levelText(50));
        assertTrue(GunsmithLevelText.isMax(50));
    }

    @Test
    void freshlyCraftedGunShowsPlainZeroWithoutMax() {
        // 现象 1 的回归点：新枪必须是 "0"，绝不是 "0 (MAX)"
        assertEquals("0", GunsmithLevelText.levelText(0));
        assertFalse(GunsmithLevelText.levelText(0).contains("MAX"));
        assertFalse(GunsmithLevelText.isMax(0));
    }

    @Test
    void levelsBelowFiftyShowPlainNumbers() {
        for (int level : new int[] {1, 2, 25, 48, 49}) {
            String text = GunsmithLevelText.levelText(level);
            assertEquals(Integer.toString(level), text, "第 " + level + " 级只显示数字");
            assertFalse(text.contains("MAX"), "第 " + level + " 级不得显示 MAX");
            assertFalse(GunsmithLevelText.isMax(level), "第 " + level + " 级不是满级");
        }
    }

    @Test
    void outOfRangeLevelsAreClampedBeforeFormatting() {
        assertEquals("0", GunsmithLevelText.levelText(-3));
        assertEquals("50 (MAX)", GunsmithLevelText.levelText(51));
        assertEquals("50 (MAX)", GunsmithLevelText.levelText(999));
        assertFalse(GunsmithLevelText.isMax(-3));
        assertTrue(GunsmithLevelText.isMax(999));
    }

    @Test
    void maxSuffixMatchesTaczNativeWording() {
        // TaCZ 原生就是 String.format("%d (MAX)", level)：前导空格 + 全大写，改了就与整合包其它地方不一致
        assertEquals(" (MAX)", GunsmithLevelText.MAX_SUFFIX);
        assertEquals(GunsmithMath.MAX_LEVEL + GunsmithLevelText.MAX_SUFFIX,
                GunsmithLevelText.levelText(GunsmithMath.MAX_LEVEL));
    }
}
