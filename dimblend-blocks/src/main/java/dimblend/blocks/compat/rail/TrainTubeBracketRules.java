package dimblend.blocks.compat.rail;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 加压管道的支架方位规则（纯函数，不碰世界）。
 *
 * <p>逐行对齐 Create 6.0.10 的两段字节码（{@code BracketBlock.java} /
 * {@code FluidPropagator.java}）：</p>
 * <ul>
 *   <li><b>直管判定</b>（{@code FluidPropagator#getStraightPipeAxis}）：把 3 根轴各两条对向的连接布尔
 *       数一遍，恰有 2 个为真且这 2 个是同一根轴的两端 → 该轴；其余（0/1 连、折角、三通、十字、全通）
 *       一律 {@code null}。</li>
 *   <li><b>可放面</b>（{@code BracketBlock#getSuitableBracket(BlockState, Direction)}）：非直管（轴
 *       {@code null}）或点击面与管道轴同轴 → 不放；否则就用点击面。</li>
 *   <li><b>朝向附加位</b>：{@code AXIS_ALONG_FIRST_COORDINATE = !(点击面轴 == Z ? 管道轴 == Y : 管道轴 == Z)}。</li>
 * </ul>
 *
 * <p>Create 的 {@code BracketBlockItem#useOn} 只把 {@code FluidPipeBlock} 当管道
 * （{@code FluidPropagator.getStraightPipeAxis} 内部用 {@code FluidPipeBlock.isPipe} 判类），
 * 本方块继承的是 {@link PipeBlock}，因此这两条规则必须在本模组里重算；判定口径必须与 Create 完全一致，
 * 否则支架会在 Create 认为"放不上"的面被放上去、或者朝向错一格。</p>
 */
public final class TrainTubeBracketRules {

    private TrainTubeBracketRules() {
    }

    /**
     * Create 的直管口径。参数就是 {@code PipeBlock} 的 6 个连接布尔（对向成对）。
     *
     * @return 直通时的那根轴；任何非直通形状返回 {@code null}
     */
    @Nullable
    public static Direction.Axis straightAxis(boolean north, boolean south, boolean east, boolean west,
            boolean up, boolean down) {
        int openSides = 0;
        Direction.Axis straight = null;

        // X 轴：WEST(-) / EAST(+)
        if (west) {
            openSides++;
        }
        if (east) {
            openSides++;
        }
        if (west && east) {
            if (straight != null) {
                return null;
            }
            straight = Direction.Axis.X;
        }

        // Y 轴：DOWN(-) / UP(+)
        if (down) {
            openSides++;
        }
        if (up) {
            openSides++;
        }
        if (down && up) {
            if (straight != null) {
                return null;
            }
            straight = Direction.Axis.Y;
        }

        // Z 轴：NORTH(-) / SOUTH(+)
        if (north) {
            openSides++;
        }
        if (south) {
            openSides++;
        }
        if (north && south) {
            if (straight != null) {
                return null;
            }
            straight = Direction.Axis.Z;
        }

        return openSides == 2 ? straight : null;
    }

    /** {@link #straightAxis(boolean, boolean, boolean, boolean, boolean, boolean)} 的方块状态入口。 */
    @Nullable
    public static Direction.Axis straightAxis(BlockState state) {
        if (!(state.getBlock() instanceof TrainPressuredTubeBlock)) {
            return null;
        }
        return straightAxis(
                state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(Direction.NORTH)),
                state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(Direction.SOUTH)),
                state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(Direction.EAST)),
                state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(Direction.WEST)),
                state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(Direction.UP)),
                state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(Direction.DOWN)));
    }

    /** 该状态能不能挂支架（= 直管）。 */
    public static boolean canHaveBracket(BlockState state) {
        return straightAxis(state) != null;
    }

    /**
     * 底面朝哪：非直管或点击面与管道轴同轴时不可放。
     *
     * <p>对应 {@code BracketBlock#getSuitableBracket} 的第一道门：{@code axis == null ||
     * clickedFace.getAxis() == axis → Optional.empty()}。</p>
     */
    public static Optional<Direction> resolveBracketFace(@Nullable Direction.Axis pipeAxis, Direction clickedFace) {
        if (pipeAxis == null || clickedFace.getAxis() == pipeAxis) {
            return Optional.empty();
        }
        return Optional.of(clickedFace);
    }

    /**
     * 支架方块的 {@code AXIS_ALONG_FIRST_COORDINATE}：Create 对"点击面轴 == Z"与其余两种情况
     * 用了不同的比较对象，这里照抄，不要化简。
     */
    public static boolean axisAlongFirstCoordinate(Direction.Axis pipeAxis, Direction bracketFace) {
        Objects.requireNonNull(pipeAxis, "pipeAxis");
        Objects.requireNonNull(bracketFace, "bracketFace");
        boolean alongFirst = bracketFace.getAxis() == Direction.Axis.Z
                ? pipeAxis == Direction.Axis.Y
                : pipeAxis == Direction.Axis.Z;
        return !alongFirst;
    }
}
