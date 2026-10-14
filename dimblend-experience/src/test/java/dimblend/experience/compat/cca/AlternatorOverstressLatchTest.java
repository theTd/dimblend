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
    void resetsOnlyAtStandstill() {
        assertTrue(AlternatorOverstressLatch.shouldReset(0.0F));
        assertFalse(AlternatorOverstressLatch.shouldReset(1.0F));
        assertFalse(AlternatorOverstressLatch.shouldReset(-1.0F));
    }
}
