package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * LOCKED DECISION #16 的两条纯口径：抽屉按命名空间认、大容量按"严格大于 32 格"认。
 * 真实判定（BE 类型 + 能力槽数）要注册表和世界，单测不碰。
 */
class LargeStorageLavaImmunityTest {

    @Test
    void drawerNamespaceIsExact() {
        assertTrue(LargeStorageLavaImmunity.isDrawerNamespace("create_item_drawers"));
        assertFalse(LargeStorageLavaImmunity.isDrawerNamespace("create"));
        assertFalse(LargeStorageLavaImmunity.isDrawerNamespace("dndecor"));
        assertFalse(LargeStorageLavaImmunity.isDrawerNamespace(null));
        assertFalse(LargeStorageLavaImmunity.isDrawerNamespace(""));
    }

    @Test
    void thresholdIsStrictlyGreaterThanThirtyTwo() {
        assertFalse(LargeStorageLavaImmunity.isLargeSlotCount(0));
        assertFalse(LargeStorageLavaImmunity.isLargeSlotCount(27));
        assertFalse(LargeStorageLavaImmunity.isLargeSlotCount(32), "32 格的单箱/最小保险库不算");
        assertTrue(LargeStorageLavaImmunity.isLargeSlotCount(33));
        assertTrue(LargeStorageLavaImmunity.isLargeSlotCount(54));
    }

    @Test
    void thresholdConstantMatchesTheRule() {
        assertTrue(LargeStorageLavaImmunity.MIN_LARGE_SLOTS_EXCLUSIVE == 32);
    }
}
