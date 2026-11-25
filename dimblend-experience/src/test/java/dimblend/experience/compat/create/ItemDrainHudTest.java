package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 分液池「蒸发中」显示判定（Audit Default #8：红色“蒸发中（7×7）”）。
 *
 * <p>只测纯判定：{@code hudLines} 本身依赖 Config 副本，必须在游戏内渲染路径调用，
 * 不进单测。</p>
 */
class ItemDrainHudTest {

    @Test
    void evaporatingNeedsWaterNetherSwitchAndRate() {
        assertTrue(ItemDrainHud.shouldShowEvaporating(500, true, true, 1));
        assertFalse(ItemDrainHud.shouldShowEvaporating(0, true, true, 1), "空池不显示蒸发");
        assertFalse(ItemDrainHud.shouldShowEvaporating(500, false, true, 1), "主世界只是灌溉");
        assertFalse(ItemDrainHud.shouldShowEvaporating(500, true, false, 1), "关蒸发开关");
        assertFalse(ItemDrainHud.shouldShowEvaporating(500, true, true, 0), "速率 0 = 不蒸发");
    }

    @Test
    void legacySnapshotNeverEvaporates() {
        assertFalse(ItemDrainHud.Snapshot.of(1500, 30).evaporating());
    }

    @Test
    void snapshotCarriesCoverageDiameter() {
        ItemDrainHud.Snapshot snapshot = new ItemDrainHud.Snapshot(700, -1, true, 7);
        assertTrue(snapshot.evaporating());
        org.junit.jupiter.api.Assertions.assertEquals(7, snapshot.coverageDiameter());
        org.junit.jupiter.api.Assertions.assertEquals(700, snapshot.waterMb());
        org.junit.jupiter.api.Assertions.assertEquals(-1, snapshot.boostTicksLeft());
    }
}
