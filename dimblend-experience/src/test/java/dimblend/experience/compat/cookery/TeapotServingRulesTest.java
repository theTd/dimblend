package dimblend.experience.compat.cookery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 茶壶一份 = 4 杯（Audit Default #9）。 */
class TeapotServingRulesTest {

    @Test
    void onePotYieldsFourServings() {
        assertEquals(4, TeapotServingRules.SERVINGS_PER_POT);
        assertEquals(4, TeapotServingRules.outputCount());
        assertEquals(4.0F, TeapotServingRules.BAR_DIVISOR);
    }

    @Test
    void barIsFullAtFourServings() {
        assertEquals(13, TeapotServingRules.barWidth(4));
        assertEquals(10, TeapotServingRules.barWidth(3));
        assertEquals(7, TeapotServingRules.barWidth(2));
        assertEquals(3, TeapotServingRules.barWidth(1));
        assertEquals(0, TeapotServingRules.barWidth(0));
    }

    @Test
    void legacyCountsNeverOverflowTheBar() {
        for (int servings = 5; servings <= 12; servings++) {
            assertEquals(13, TeapotServingRules.barWidth(servings), "旧存档 " + servings + " 杯");
        }
        assertEquals(13, TeapotServingRules.clampBarWidth(40));
        assertEquals(0, TeapotServingRules.clampBarWidth(-3));
        assertEquals(5, TeapotServingRules.clampBarWidth(5));
    }

    @Test
    void negativeServingsAreEmpty() {
        assertEquals(0, TeapotServingRules.barWidth(-1));
    }
}
