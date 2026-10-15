package dimblend.experience.compat.cca;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AlternatorOverstressLatchTest {

    @Test
    void entersOnlyWhenOverstressedAndDrivenAndOnLedger() {
        assertTrue(AlternatorOverstressLatch.shouldEnter(true, 64.0F, true));
        assertTrue(AlternatorOverstressLatch.shouldEnter(true, -64.0F, true));
        assertFalse(AlternatorOverstressLatch.shouldEnter(false, 64.0F, true));
        assertFalse(AlternatorOverstressLatch.shouldEnter(true, 0.0F, true));
        // 读档首 tick 账本未结算（members 无本机）不得进入——防 addSilently 跳过结算
        assertFalse(AlternatorOverstressLatch.shouldEnter(true, 64.0F, false));
    }

    @Test
    void resetsWhenSpeedDiffersFromLatchedRecord() {
        // 与锁存记录值相同：不解锁（同拓扑同源逐 bit 相同，含负向）
        assertFalse(AlternatorOverstressLatch.shouldReset(64.0F, 64.0F));
        assertFalse(AlternatorOverstressLatch.shouldReset(-64.0F, -64.0F));
        // 与记录值不同即解锁（网络转速变化，不加阈值）
        assertTrue(AlternatorOverstressLatch.shouldReset(63.0F, 64.0F));
        assertTrue(AlternatorOverstressLatch.shouldReset(96.0F, 64.0F));
        // 停机归零天然解锁（锁存记录值必非零，为旧"归零才解锁"判据超集）
        assertTrue(AlternatorOverstressLatch.shouldReset(0.0F, 64.0F));
        // 正负换向解锁
        assertTrue(AlternatorOverstressLatch.shouldReset(-64.0F, 64.0F));
        assertTrue(AlternatorOverstressLatch.shouldReset(64.0F, -64.0F));
    }
}
