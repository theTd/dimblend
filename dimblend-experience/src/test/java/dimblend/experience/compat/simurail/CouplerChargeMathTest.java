package dimblend.experience.compat.simurail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * E10/E11 扳手充能口径（用户两条口径的纯函数面）。
 *
 * <p>口径原文：
 * <ul>
 *   <li>「扳手右键改为使车勾 充能/取消充能」——切换语义在 {@code CouplerCharge#toggleCharge}，
 *       需要 BE，不在单测范围。</li>
 *   <li>「扳手充能的车勾在断钩 且 附近2格内没有其他sable的车钩后 会自动取消充能」——
 *       判定本体是 {@link CouplerChargeMath#shouldDecharge}，本类钉死它。</li>
 * </ul>
 */
class CouplerChargeMathTest {

    /** 半径就是用户说的「2格」，改动即违约。 */
    @Test
    void neighbourRadiusIsTheTwoBlocksTheUserAskedFor() {
        assertTrue(CouplerChargeMath.NEIGHBOUR_RADIUS == 2.0D,
                "口径是「附近2格」，半径改成别的值必须有用户新口径");
    }

    /** 三个条件同时成立才取消：已充能 + 已断钩 + 附近没有别的车钩。 */
    @Test
    void dechargesOnlyWhenChargedDetachedAndAlone() {
        assertTrue(CouplerChargeMath.shouldDecharge(true, false, false));
    }

    /** 未充能谈不上取消（幂等，不产生额外状态写入）。 */
    @Test
    void neverDechargesWhatIsNotCharged() {
        assertFalse(CouplerChargeMath.shouldDecharge(false, false, false));
        assertFalse(CouplerChargeMath.shouldDecharge(false, true, false));
        assertFalse(CouplerChargeMath.shouldDecharge(false, false, true));
        assertFalse(CouplerChargeMath.shouldDecharge(false, true, true));
    }

    /** 还挂着伙伴 = 未断钩，口径的第一个条件就不成立。 */
    @Test
    void keepsChargeWhileStillCoupled() {
        assertFalse(CouplerChargeMath.shouldDecharge(true, true, false));
        assertFalse(CouplerChargeMath.shouldDecharge(true, true, true));
    }

    /** 断钩了但附近还有别的车钩：充能保留（准备再挂），口径的第二个条件不成立。 */
    @Test
    void keepsChargeWhileAnotherCouplerIsNearby() {
        assertFalse(CouplerChargeMath.shouldDecharge(true, false, true));
    }

    /** 半径是闭区间：正好 2 格算「在内」，所以 2.0² 通过、再大一点不通过。 */
    @Test
    void radiusComparisonIsInclusiveAtTheBoundary() {
        assertTrue(CouplerChargeMath.isWithin(0.0D, 2.0D));
        assertTrue(CouplerChargeMath.isWithin(4.0D, 2.0D));
        assertTrue(CouplerChargeMath.isWithin(3.999999D, 2.0D));
        assertFalse(CouplerChargeMath.isWithin(4.000001D, 2.0D));
        assertFalse(CouplerChargeMath.isWithin(9.0D, 2.0D));
    }
}