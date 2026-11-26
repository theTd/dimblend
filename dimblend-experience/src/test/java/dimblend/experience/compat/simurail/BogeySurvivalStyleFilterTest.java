package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 生存模式转向架菜单收缩口径（2026-11-26 slice-1 #1B）。
 *
 * <p>钉死三件事：白名单顺序（隐形 → 小型宽轨标准 → 大型宽轨苏格兰轭 → 巨型宽轨苏格兰轭）、
 * 只留一个条目级分类时菜单的行数/页数不会越界、创造模式完全不进收缩分支。</p>
 */
class BogeySurvivalStyleFilterTest {

    private static final ResourceLocation INVISIBLE = ResourceLocation.parse("railways:invisible");
    private static final ResourceLocation WIDE_STANDARD = ResourceLocation.parse("railways:wide/standard");
    private static final ResourceLocation WIDE_SCOTCH_LARGE =
            ResourceLocation.parse("railways:wide/scotch_yoke/large");
    private static final ResourceLocation WIDE_SCOTCH_XXL =
            ResourceLocation.parse("railways:wide/scotch_yoke/comically_large");

    private static final Predicate<ResourceLocation> EVERYTHING = id -> true;

    @Test
    void whitelistKeepsTheFourUserNamedStylesInUpstreamRegistrationOrder() {
        assertEquals(List.of(INVISIBLE, WIDE_STANDARD, WIDE_SCOTCH_LARGE, WIDE_SCOTCH_XXL),
                SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS);
        assertTrue(BogeySurvivalStyleFilter.isAllowed(INVISIBLE));
        assertTrue(BogeySurvivalStyleFilter.isAllowed(WIDE_SCOTCH_XXL));
        assertFalse(BogeySurvivalStyleFilter.isAllowed(ResourceLocation.parse("railways:narrow/standard")));
        assertFalse(BogeySurvivalStyleFilter.isAllowed(ResourceLocation.parse("railways:scotch_yoke/1")));
        assertFalse(BogeySurvivalStyleFilter.isAllowed(null));
    }

    @Test
    void selectableIdsIgnoresInputOrderDuplicatesAndUnknownIds() {
        List<ResourceLocation> shuffled = List.of(WIDE_SCOTCH_XXL, ResourceLocation.parse("railways:monobogey"),
                WIDE_SCOTCH_LARGE, WIDE_SCOTCH_XXL, INVISIBLE, WIDE_STANDARD);
        assertEquals(List.of(INVISIBLE, WIDE_STANDARD, WIDE_SCOTCH_LARGE, WIDE_SCOTCH_XXL),
                BogeySurvivalStyleFilter.selectableIds(shuffled, EVERYTHING));
    }

    @Test
    void selectableIdsDropsEntriesThePredicateRejects() {
        // 倒置载具只允许可倒置样式：这里模拟"隐形不可倒置"
        List<ResourceLocation> available = SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS;
        assertEquals(List.of(WIDE_STANDARD, WIDE_SCOTCH_LARGE, WIDE_SCOTCH_XXL),
                BogeySurvivalStyleFilter.selectableIds(available, id -> !id.equals(INVISIBLE)));
        assertEquals(List.of(), BogeySurvivalStyleFilter.selectableIds(available, id -> false));
        assertEquals(List.of(), BogeySurvivalStyleFilter.selectableIds(List.of(), EVERYTHING));
    }

    @Test
    void selectableIdsSkipsNullCandidates() {
        assertEquals(List.of(INVISIBLE),
                BogeySurvivalStyleFilter.selectableIds(java.util.Arrays.asList(null, INVISIBLE), EVERYTHING));
    }

    @Test
    void creativeModeNeverEntersTheSurvivalBranch() {
        assertFalse(BogeySurvivalStyleFilter.shouldRestrict(false, false), "没有玩家时不收缩");
        assertFalse(BogeySurvivalStyleFilter.shouldRestrict(true, true), "创造模式完全不收缩");
        assertTrue(BogeySurvivalStyleFilter.shouldRestrict(true, false));
    }

    @Test
    void singleEntryLevelCategoryLeadsToDepthZeroAndSixRows() {
        // 只保留一个条目级分类 ⇒ path.size() == 1 ⇒ subPathDepth == 0
        assertEquals(0, Math.max(0, Math.min(BogeySurvivalStyleFilter.MAX_SUB_PATH_DEPTH, 1 - 1)));
        assertEquals(6, BogeySurvivalStyleFilter.rowsPerPage(0));
        assertEquals(5, BogeySurvivalStyleFilter.rowsPerPage(1));
        assertEquals(3, BogeySurvivalStyleFilter.rowsPerPage(3));
        assertEquals(3, BogeySurvivalStyleFilter.rowsPerPage(9), "超过 3 层按 3 层算");
        assertEquals(6, BogeySurvivalStyleFilter.rowsPerPage(-1), "负层数按 0 层算");
    }

    @Test
    void fourAllowedStylesFitOnePageAndNeverProduceOutOfRangeRows() {
        int rowsPerPage = BogeySurvivalStyleFilter.rowsPerPage(0);
        int entries = BogeySurvivalStyleFilter.selectableIds(
                SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS, EVERYTHING).size();
        assertEquals(4, entries);
        assertEquals(1, BogeySurvivalStyleFilter.pageCount(entries, rowsPerPage), "四项一页装完，翻页控件只有第 1 页");

        for (int row = 0; row < entries; row++) {
            assertEquals(row, BogeySurvivalStyleFilter.entryIndex(0, row, rowsPerPage, entries));
        }
        assertEquals(-1, BogeySurvivalStyleFilter.entryIndex(0, 4, rowsPerPage, entries), "第 5 行留空");
        assertEquals(-1, BogeySurvivalStyleFilter.entryIndex(0, 5, rowsPerPage, entries), "第 6 行留空");
        assertEquals(-1, BogeySurvivalStyleFilter.entryIndex(1, 0, rowsPerPage, entries), "没有第 2 页");
    }

    @Test
    void pageMathMatchesTheScreenEvenWhenEverythingIsHidden() {
        assertEquals(1, BogeySurvivalStyleFilter.pageCount(0, 6), "一条都没有时仍只有第 1 页");
        assertEquals(2, BogeySurvivalStyleFilter.pageCount(7, 6));
        assertEquals(1, BogeySurvivalStyleFilter.pageCount(4, 0), "rowsPerPage 为 0 时不除零");
        assertEquals(-1, BogeySurvivalStyleFilter.entryIndex(-1, 0, 6, 4));
        assertEquals(-1, BogeySurvivalStyleFilter.entryIndex(0, -1, 6, 4));
        assertEquals(-1, BogeySurvivalStyleFilter.entryIndex(0, 0, 0, 4));
    }
}
