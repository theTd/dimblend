package dimblend.experience.curse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 绑定诅咒可以摘除：只推翻「带诅咒的一件被卡在槽里」这一种判定，别的判定留给原版。
 *
 * <p>用户口径（2026-11-03）：生存模式要能脱下带绑定诅咒的装备；脱下时仍挂本 mod 的
 * 60 分钟「绑定诅咒」效果。这里只钉前者，后者由 {code CurseEvents} 的装备变化事件负责。
 */
class BindingCurseRulesTest {

    @Test
    void aCursedPieceMayBeRemovedInSurvivalAndInCreative() {
        assertTrue(BindingCurseRules.mayRemoveCursedArmour(false, false, true),
                "生存模式必须能脱下带绑定诅咒的装备");
        assertTrue(BindingCurseRules.mayRemoveCursedArmour(true, false, true),
                "创造模式本来就能脱，改动后仍然能");
    }

    @Test
    void onlyTheCurseCaseIsOverruled() {
        assertTrue(BindingCurseRules.overrulesVanillaRefusal(false, true),
                "非空且带诅咒：只有这一种要被放行");
        assertFalse(BindingCurseRules.overrulesVanillaRefusal(true, true),
                "空槽没有东西可脱，不能把判定改成允许");
        assertFalse(BindingCurseRules.overrulesVanillaRefusal(false, false),
                "不带诅咒的装备交给原版判定，不能顺带放行");
        assertFalse(BindingCurseRules.overrulesVanillaRefusal(true, false),
                "空槽且不带诅咒：同样交给原版");
    }

    @Test
    void gameModeNoLongerChangesTheAnswer() {
        for (boolean slotEmpty : new boolean[] {true, false}) {
            for (boolean cursed : new boolean[] {true, false}) {
                assertTrue(
                        BindingCurseRules.mayRemoveCursedArmour(true, slotEmpty, cursed)
                                == BindingCurseRules.mayRemoveCursedArmour(false, slotEmpty, cursed),
                        "创造与生存必须得到同一个答案：empty=" + slotEmpty + " cursed=" + cursed);
            }
        }
    }
}