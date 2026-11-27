package dimblend.blocks.compat.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Direction;

/**
 * 支架规则的钉死测试：口径必须与 Create 6.0.10 的
 * {@code FluidPropagator#getStraightPipeAxis} / {@code BracketBlock#getSuitableBracket} 完全一致，
 * 否则支架会在错误的面被放上去或朝向错一格。
 */
class TrainTubeBracketRulesTest {

    // 参数序固定为 (north, south, east, west, up, down)，与 PipeBlock 的属性一一对应。

    @Test
    void straightThroughAlongEachAxis() {
        assertEquals(Direction.Axis.X, TrainTubeBracketRules.straightAxis(false, false, true, true, false, false));
        assertEquals(Direction.Axis.Y, TrainTubeBracketRules.straightAxis(false, false, false, false, true, true));
        assertEquals(Direction.Axis.Z, TrainTubeBracketRules.straightAxis(true, true, false, false, false, false));
    }

    @Test
    void emptyTubeIsNotStraight() {
        assertNull(TrainTubeBracketRules.straightAxis(false, false, false, false, false, false));
    }

    @Test
    void singleEndIsNotStraight() {
        assertNull(TrainTubeBracketRules.straightAxis(true, false, false, false, false, false), "north");
        assertNull(TrainTubeBracketRules.straightAxis(false, true, false, false, false, false), "south");
        assertNull(TrainTubeBracketRules.straightAxis(false, false, true, false, false, false), "east");
        assertNull(TrainTubeBracketRules.straightAxis(false, false, false, true, false, false), "west");
        assertNull(TrainTubeBracketRules.straightAxis(false, false, false, false, true, false), "up");
        assertNull(TrainTubeBracketRules.straightAxis(false, false, false, false, false, true), "down");
    }

    @Test
    void elbowIsNotStraight() {
        // 两个连接但不在同一根轴：数量够了也不能算直管
        assertNull(TrainTubeBracketRules.straightAxis(true, false, true, false, false, false), "north+east");
        assertNull(TrainTubeBracketRules.straightAxis(true, false, false, false, true, false), "north+up");
        assertNull(TrainTubeBracketRules.straightAxis(false, false, false, true, false, true), "west+down");
        assertNull(TrainTubeBracketRules.straightAxis(false, false, true, false, false, true), "east+down");
    }

    @Test
    void threeWayAndWiderAreNotStraight() {
        assertNull(TrainTubeBracketRules.straightAxis(true, true, false, false, true, false), "T: north+south+up");
        assertNull(TrainTubeBracketRules.straightAxis(true, false, true, false, true, false), "三通: north+east+up");
        assertNull(TrainTubeBracketRules.straightAxis(true, true, true, true, false, false), "十字: 水平四通");
        assertNull(TrainTubeBracketRules.straightAxis(true, true, false, false, true, true), "两个整轴: 4 连");
        assertNull(TrainTubeBracketRules.straightAxis(true, true, true, true, true, true), "全通");
    }

    @Test
    void bracketFaceRejectedAlongPipeAxis() {
        for (Direction face : Direction.values()) {
            assertTrue(TrainTubeBracketRules.resolveBracketFace(null, face).isEmpty(),
                    "非直管不能放支架: " + face);
        }
        for (Direction.Axis axis : Direction.Axis.values()) {
            for (Direction face : Direction.values()) {
                Optional<Direction> resolved = TrainTubeBracketRules.resolveBracketFace(axis, face);
                if (face.getAxis() == axis) {
                    assertTrue(resolved.isEmpty(), "点击面与管道轴同轴应拒绝: " + axis + " / " + face);
                } else {
                    assertEquals(Optional.of(face), resolved, "垂直于管道轴的面应可用: " + axis + " / " + face);
                }
            }
        }
    }

    @Test
    void axisAlongFirstCoordinateMatrix() {
        // 管道 X：所有可用面 -> true
        assertTrue(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.X, Direction.UP));
        assertTrue(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.X, Direction.DOWN));
        assertTrue(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.X, Direction.NORTH));
        assertTrue(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.X, Direction.SOUTH));

        // 管道 Y：点击 X 向面 -> true；点击 Z 向面 -> false（Create 对 Z 走另一条比较）
        assertTrue(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Y, Direction.EAST));
        assertTrue(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Y, Direction.WEST));
        assertFalse(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Y, Direction.NORTH));
        assertFalse(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Y, Direction.SOUTH));

        // 管道 Z：所有可用面 -> false
        assertFalse(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Z, Direction.UP));
        assertFalse(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Z, Direction.DOWN));
        assertFalse(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Z, Direction.EAST));
        assertFalse(TrainTubeBracketRules.axisAlongFirstCoordinate(Direction.Axis.Z, Direction.WEST));
    }

    @Test
    void lookDirectionFallbackStillResolves() {
        // 竖直管道、从端面右键：点击面被拒，退回 Direction.orderedByNearest(player)[0].getOpposite()
        Direction.Axis axis = Direction.Axis.Y;
        assertTrue(TrainTubeBracketRules.resolveBracketFace(axis, Direction.UP).isEmpty());
        assertTrue(TrainTubeBracketRules.resolveBracketFace(axis, Direction.DOWN).isEmpty());

        Direction fallback = Direction.NORTH;
        Optional<Direction> resolved = TrainTubeBracketRules.resolveBracketFace(axis, fallback);
        assertEquals(Optional.of(fallback), resolved);
        assertFalse(TrainTubeBracketRules.axisAlongFirstCoordinate(axis, fallback));
    }
}
