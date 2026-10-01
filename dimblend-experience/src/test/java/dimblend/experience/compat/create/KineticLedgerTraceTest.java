package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 诊断探针的账本事件环：容量封顶、旧→新顺序、总数计未挤出的全部事件（纯 Java）。 */
class KineticLedgerTraceTest {

    private static KineticLedgerTrace.Event event(long tick) {
        return new KineticLedgerTrace.Event(tick, "ADD", "1, 2, 3", false, 0.0F, 0.0F, 1, 0, 0.0F, 0.0F, 0, true);
    }

    @Test
    void emptyTraceReturnsNothing() {
        KineticLedgerTrace trace = new KineticLedgerTrace();
        assertTrue(trace.recent(10).isEmpty());
        assertEquals(0, trace.total());
    }

    @Test
    void recentIsOldestToNewestAndLimited() {
        KineticLedgerTrace trace = new KineticLedgerTrace();
        for (int i = 1; i <= 5; i++) {
            trace.record(event(i));
        }
        List<KineticLedgerTrace.Event> last3 = trace.recent(3);
        assertEquals(3, last3.size());
        assertEquals(3, last3.get(0).serverTick());
        assertEquals(5, last3.get(2).serverTick());
    }

    @Test
    void ringKeepsOnlyNewestCapacityButCountsAll() {
        KineticLedgerTrace trace = new KineticLedgerTrace();
        int n = KineticLedgerTrace.CAPACITY + 10;
        for (int i = 1; i <= n; i++) {
            trace.record(event(i));
        }
        List<KineticLedgerTrace.Event> all = trace.recent(1000);
        assertEquals(KineticLedgerTrace.CAPACITY, all.size());
        assertEquals(11, all.get(0).serverTick());
        assertEquals(n, all.get(all.size() - 1).serverTick());
        assertEquals(n, trace.total());
    }
}
