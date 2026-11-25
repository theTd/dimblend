package dimblend.blocks.compat.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 列车加压管道网络规则自检：100mB 容量、换液 SIMULATE/EXECUTE、拓扑清空、端点资格。 */
class TrainTubeNetworkMathTest {

    @Test
    void capacityIsOneHundredMillibuckets() {
        assertEquals(100, TrainTubeNetworkMath.CAPACITY_MB);
        assertEquals(100, TrainTubeNetworkMath.simulateFill(0, true, 500));
        assertEquals(100, TrainTubeNetworkMath.simulateFill(0, false, 500));
        assertEquals(100, TrainTubeNetworkMath.poolAmountAfterFill(0, true, 100));
        assertEquals(100, TrainTubeNetworkMath.poolAmountAfterFill(99, true, 9));
    }

    @Test
    void sameFluidFillsOnlyRemainingRoom() {
        // 池里已有 40 水，同种灌 80 -> 只能吃 60
        assertEquals(60, TrainTubeNetworkMath.simulateFill(40, true, 80));
        assertEquals(60, TrainTubeNetworkMath.acceptedAmount(40, true, 80, 60));
        assertEquals(100, TrainTubeNetworkMath.poolAmountAfterFill(40, true, 60));
        // 满池 -> 0，池内余量保持 100
        assertEquals(0, TrainTubeNetworkMath.simulateFill(100, true, 10));
        assertEquals(100, TrainTubeNetworkMath.poolAmountAfterFill(100, true, 0));
    }

    @Test
    void differentFluidSimulateIsAlwaysPositive() {
        // 池里 90 水，灌 100 岩浆（不同种）-> SIMULATE 必须给出正数
        assertEquals(100, TrainTubeNetworkMath.simulateFill(90, false, 100));
        // 即便灌入很少也必须 > 0，否则泵误判"灌不进"
        assertEquals(5, TrainTubeNetworkMath.simulateFill(99, false, 5));
        // EXECUTE：先清后灌，池里只剩新液
        int simulated = TrainTubeNetworkMath.simulateFill(90, false, 100);
        int accepted = TrainTubeNetworkMath.acceptedAmount(90, false, 100, simulated);
        assertEquals(100, accepted);
        assertEquals(100, TrainTubeNetworkMath.poolAmountAfterFill(90, false, accepted), "换液后旧液必须被清掉");
        // 换液但灌得少
        assertEquals(5, TrainTubeNetworkMath.poolAmountAfterFill(90, false, 5));
    }

    @Test
    void zeroOrNegativeInputNeverAccepts() {
        assertEquals(0, TrainTubeNetworkMath.simulateFill(40, true, 0));
        assertEquals(0, TrainTubeNetworkMath.simulateFill(40, true, -7));
        assertEquals(0, TrainTubeNetworkMath.acceptedAmount(40, true, 10, 0));
        assertEquals(40, TrainTubeNetworkMath.poolAmountAfterFill(40, true, 0), "没接受任何液体时池不变");
    }

    @Test
    void drainIsCappedByPool() {
        assertEquals(0, TrainTubeNetworkMath.drainAmount(0, 50));
        assertEquals(0, TrainTubeNetworkMath.drainAmount(30, 0));
        assertEquals(30, TrainTubeNetworkMath.drainAmount(30, 50));
        assertEquals(20, TrainTubeNetworkMath.drainAmount(30, 20));
    }

    @Test
    void onlySingleConnectionTubesCanAnchor() {
        assertEquals(1, TrainTubeNetworkMath.ENDPOINT_LOCAL_CONNECTIONS);
        assertTrue(TrainTubeNetworkMath.isValidEndpoint(1));
        assertFalse(TrainTubeNetworkMath.isValidEndpoint(0));
        assertFalse(TrainTubeNetworkMath.isValidEndpoint(2), "十字/直角接头不能当锚点");
        assertFalse(TrainTubeNetworkMath.isValidEndpoint(6));
    }

    @Test
    void linkDistanceIsBoundedAtSixteenBlocks() {
        assertEquals(16, TrainTubeNetworkMath.MAX_LINK_DISTANCE);
        assertTrue(TrainTubeNetworkMath.withinLinkDistance(16, 0, 0));
        assertFalse(TrainTubeNetworkMath.withinLinkDistance(16.001, 0, 0));
        assertTrue(TrainTubeNetworkMath.withinLinkDistance(9, 12, 0), "9-12-15 直角三角形仍在 16 内");
        assertFalse(TrainTubeNetworkMath.withinLinkDistance(10, 10, 10), "17.3 超限");
        assertTrue(TrainTubeNetworkMath.withinLinkDistance(0, 0, 0));
    }

    @Test
    void topologyChangeAlwaysClears() {
        assertEquals(0, TrainTubeNetworkMath.clearOnTopologyChange());
        // 清空之后再灌同种液体：因为池已归零，能吃到满容量
        assertEquals(100, TrainTubeNetworkMath.simulateFill(TrainTubeNetworkMath.clearOnTopologyChange(), true, 100));
    }
}
