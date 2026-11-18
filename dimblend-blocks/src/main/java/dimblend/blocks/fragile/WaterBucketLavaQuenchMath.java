package dimblend.blocks.fragile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 水桶 / 蓝冰冷却岩浆的纯几何与回溯判定：仅依赖 {@link BlockPos} 与 {@link Direction}，
 * 不触达 {@code Blocks}/{@code Items} 注册表，可在无游戏引导（bootstrap）的 JVM 中直接单测。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>命中格为<b>岩浆源头</b>：固化以命中格为中心的 {@code 3x3x3} 立方体内的所有岩浆源头为黑曜石
 *       （立方体内的非源头流动岩浆与其他方块保持原样）。</li>
 *   <li>命中格为<b>岩浆流</b>：沿上游回溯源头：
 *     <ul>
 *       <li>源头在 {@value #MAX_SOLIDIFY_TRACE_STEPS} 格以内（含）：源头变为黑曜石，从右键位置追溯到源头前的岩浆流变为易碎石头；</li>
 *       <li>源头在 {@value #MAX_SOLIDIFY_TRACE_STEPS} 格以外：不固化方块，从右键位置沿途至源头依次播放烟雾粒子与火焰熄灭音效；
 *           其中最先 {@value #OVER_16_CUT_LIMIT} 格纯岩浆流在效果播放后被直接删除（紧急截断）。</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class WaterBucketLavaQuenchMath {

    /** 岩浆源头固化半径：dx/dy/dz ∈ [-1, 1]，即 3x3x3 共 27 格。 */
    public static final int SOURCE_CUBE_RADIUS = 1;

    /** 岩浆流回溯源头可固化的最大步数（含 16 格）。 */
    public static final int MAX_SOLIDIFY_TRACE_STEPS = 16;

    /** 超过 16 格时沿途播放烟雾的最大回溯步数上限（防止极端大瀑布无界搜索）。 */
    public static final int MAX_SMOKE_TRACE_STEPS = 128;

    /** 超过 16 格时，从右键格向源头追溯途中最先被直接删除的岩浆流格数。 */
    public static final int OVER_16_CUT_LIMIT = 8;

    /**
     * 决策开关（{@code lava-over-16-consume}）：源头超出 16 格、仅播放沿途烟雾与熄灭音效时，
     * 生存模式下是否仍消耗水桶中的水 / 1 个蓝冰。默认 {@code true}（化作烟雾蒸发）。
     */
    public static final boolean CONSUME_WHEN_SOURCE_OVER_16 = true;

    private static final Direction[] HORIZONTAL_DIRS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /**
     * 单格流体采样快照。
     *
     * @param isLava    是否为纯岩浆液体格（{@code FluidTags.LAVA && BlockState#liquid()} 且区块已就位）
     * @param isSource  是否为岩浆源头（{@code FluidState#isSource()}）
     * @param isFalling 是否为下落态岩浆流（{@code FlowingFluid.FALLING == true}）
     * @param amount    流体高度档位（源头/下落为 8，水平流动为 1..7，非岩浆为 0）
     */
    public record LavaCellSample(boolean isLava, boolean isSource, boolean isFalling, int amount) {
        public static final LavaCellSample NON_LAVA = new LavaCellSample(false, false, false, 0);

        public static LavaCellSample source() {
            return new LavaCellSample(true, true, false, 8);
        }

        public static LavaCellSample falling() {
            return new LavaCellSample(true, false, true, 8);
        }

        public static LavaCellSample flowing(int amount) {
            return new LavaCellSample(true, false, false, amount);
        }
    }

    /** 岩浆流上游回溯分类。 */
    public enum FlowTraceKind {
        /** 在 {@value #MAX_SOLIDIFY_TRACE_STEPS} 步内找到源头：源头变黑曜石、沿途岩浆流变易碎石头。 */
        SOURCE_WITHIN_16,
        /** 源头在 {@value #MAX_SOLIDIFY_TRACE_STEPS} 步以外：不改方块，沿途依次播放烟雾与熄灭音效。 */
        SOURCE_OVER_16,
        /** 上游 16 步内已无源头（孤立残余岩浆流）：将回溯到的残流固化为易碎石头。 */
        ORPHAN_FLOW
    }

    /**
     * 岩浆流回溯结果。
     *
     * @param kind                      回溯分类
     * @param pathFromClickedToUpstream 从右键点击格（下标 0）到上游终点/源头（末尾下标）的有序格列表（不可变）
     */
    public record FlowTraceResult(FlowTraceKind kind, List<BlockPos> pathFromClickedToUpstream) {
        public FlowTraceResult {
            Objects.requireNonNull(kind, "kind");
            pathFromClickedToUpstream = List.copyOf(pathFromClickedToUpstream);
            if (pathFromClickedToUpstream.isEmpty()) {
                throw new IllegalArgumentException("pathFromClickedToUpstream must not be empty");
            }
        }

        /** 从右键格走到末尾格的步数（同格为 0，紧邻为 1）。 */
        public int steps() {
            return pathFromClickedToUpstream.size() - 1;
        }
    }

    /** 以 {@code center} 为中心的 3x3x3 全部 27 格（含中心格），顺序 dx → dy → dz。 */
    public static List<BlockPos> cube3x3x3(BlockPos center) {
        int side = SOURCE_CUBE_RADIUS * 2 + 1;
        List<BlockPos> cells = new ArrayList<>(side * side * side);
        for (int dx = -SOURCE_CUBE_RADIUS; dx <= SOURCE_CUBE_RADIUS; dx++) {
            for (int dy = -SOURCE_CUBE_RADIUS; dy <= SOURCE_CUBE_RADIUS; dy++) {
                for (int dz = -SOURCE_CUBE_RADIUS; dz <= SOURCE_CUBE_RADIUS; dz++) {
                    cells.add(center.offset(dx, dy, dz));
                }
            }
        }
        return List.copyOf(cells);
    }

    /** 源头模式 3x3x3 内单格判定：仅纯岩浆源头固化为黑曜石，岩浆流与非液体方块一律不动。 */
    public static boolean shouldSolidifyInSourceCube(boolean isLavaFluid, boolean isSourceFluid, boolean isLiquidBlock) {
        return isLavaFluid && isSourceFluid && isLiquidBlock;
    }

    /**
     * 从被点击的岩浆流格 {@code clickedPos} 沿流体上游最短路径（BFS）回溯源头。
     *
     * <p>上游判据与原版 {@code FlowingFluid} 扩散规则严格对称：</p>
     * <ul>
     *   <li>正上方 {@code cur.above()} 若为岩浆，必是 {@code cur} 的上游（下落供液）；</li>
     *   <li>若 {@code cur} 非下落态，同一水平面的四邻格 {@code next} 在「是源头 / 是下落态 / 流体档位严格大于 {@code cur}」
     *       时为合法上游；</li>
     *   <li>若 {@code cur} 为下落态但上方已无岩浆（如测试手工摆放或断流瞬间），允许同层紧邻的岩浆源头作为兜底上游。</li>
     * </ul>
     */
    public static FlowTraceResult traceUpstream(BlockPos clickedPos, Function<BlockPos, LavaCellSample> sampler) {
        BlockPos start = clickedPos.immutable();
        LavaCellSample startSample = sampler.apply(start);
        if (!startSample.isLava() || startSample.isSource()) {
            return new FlowTraceResult(FlowTraceKind.SOURCE_WITHIN_16, List.of(start));
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> distance = new HashMap<>();

        queue.add(start);
        parent.put(start, null);
        distance.put(start, 0);

        BlockPos farthestPos = start;
        int farthestDist = 0;

        while (!queue.isEmpty()) {
            BlockPos cur = queue.removeFirst();
            int curDist = distance.get(cur);
            LavaCellSample curSample = sampler.apply(cur);

            if (curDist > farthestDist) {
                farthestDist = curDist;
                farthestPos = cur;
            }
            if (curSample.isSource()) {
                List<BlockPos> path = reconstructPath(parent, cur);
                FlowTraceKind kind = curDist <= MAX_SOLIDIFY_TRACE_STEPS
                        ? FlowTraceKind.SOURCE_WITHIN_16
                        : FlowTraceKind.SOURCE_OVER_16;
                return new FlowTraceResult(kind, path);
            }
            if (curDist >= MAX_SMOKE_TRACE_STEPS) {
                continue;
            }

            for (BlockPos next : upstreamCandidates(cur, curSample, sampler)) {
                if (distance.containsKey(next)) {
                    continue;
                }
                parent.put(next, cur);
                distance.put(next, curDist + 1);
                queue.addLast(next);
            }
        }

        List<BlockPos> fallbackPath = reconstructPath(parent, farthestPos);
        FlowTraceKind fallbackKind = farthestDist > MAX_SOLIDIFY_TRACE_STEPS
                ? FlowTraceKind.SOURCE_OVER_16
                : FlowTraceKind.ORPHAN_FLOW;
        return new FlowTraceResult(fallbackKind, fallbackPath);
    }

    private static List<BlockPos> upstreamCandidates(BlockPos cur, LavaCellSample curSample,
            Function<BlockPos, LavaCellSample> sampler) {
        List<BlockPos> result = new ArrayList<>(5);
        BlockPos above = cur.above();
        LavaCellSample aboveSample = sampler.apply(above);
        if (aboveSample.isLava()) {
            result.add(above);
        }

        if (!curSample.isFalling()) {
            for (Direction dir : HORIZONTAL_DIRS) {
                BlockPos next = cur.relative(dir);
                LavaCellSample nextSample = sampler.apply(next);
                if (!nextSample.isLava()) {
                    continue;
                }
                if (nextSample.isSource() || nextSample.isFalling() || nextSample.amount() > curSample.amount()) {
                    result.add(next);
                }
            }
        } else if (!aboveSample.isLava()) {
            for (Direction dir : HORIZONTAL_DIRS) {
                BlockPos next = cur.relative(dir);
                LavaCellSample nextSample = sampler.apply(next);
                if (nextSample.isLava() && nextSample.isSource()) {
                    result.add(next);
                }
            }
        }
        return result;
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> parent, BlockPos target) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cursor = target;
        while (cursor != null) {
            reversed.add(cursor);
            cursor = parent.get(cursor);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /** 生存/冒险消耗冷却物（水桶回空桶、蓝冰 -1）；创造（{@code hasInfiniteMaterials}）保留。 */
    public static boolean shouldConsumeCoolant(boolean hasInfiniteMaterials, boolean sourceOver16) {
        if (hasInfiniteMaterials) {
            return false;
        }
        return !sourceOver16 || CONSUME_WHEN_SOURCE_OVER_16;
    }

    /**
     * 源头超出 16 格时的紧急截断：从右键格（步 0）向源头追溯，前 {@code deleteFirstCount}
     * 格里的纯岩浆流被删除。源头本身与更远的岩浆流不动。
     *
     * @param stepIndex        回溯步数（右键格为 0，逐步向源头递增）
     * @param deleteFirstCount 允许删除的前缀长度，生产路径使用 {@link #OVER_16_CUT_LIMIT}
     * @param isLavaFluid      该格流体是岩浆且方块本体是液体方块
     * @param isSource         该格是岩浆源头
     */
    public static boolean shouldDeleteOnOver16Trail(int stepIndex, int deleteFirstCount,
            boolean isLavaFluid, boolean isSource) {
        return stepIndex >= 0 && stepIndex < deleteFirstCount && isLavaFluid && !isSource;
    }

    private WaterBucketLavaQuenchMath() {
    }
}
