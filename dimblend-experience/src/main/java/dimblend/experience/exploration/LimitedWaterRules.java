package dimblend.experience.exploration;

import javax.annotation.Nullable;

import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * G3 有限水（服务端，rotating 维度内）：每次经 {@code Level#setBlock} 写入源水（water8，
 * 即 {@code Blocks.WATER} 且 {@code LEVEL=0}）时判定是否改写为流动 water7。
 *
 * <p>判定（无论写入来源：玩家倒桶、玩家破冰、管道、发射器、机械手、冰融化、水流成池）：</p>
 * <ol>
 * <li>以源水格中心为球心、{@link LimitedWaterMath#DECIDER_RADIUS} 格内找最近的非旁观玩家
 * （三维直线距离；Sable 载具上的玩家与方块按真实世界坐标计距）。</li>
 * <li>该玩家为创造模式：不检测，原样写入。</li>
 * <li>其余情况（生存/冒险，或半径内无人）：数水平四邻中源水/冰/浮冰/蓝冰，
 * ≥{@link LimitedWaterMath#MIN_NEIGHBOURS_TO_KEEP} 保留源水，否则改写为 water7。</li>
 * </ol>
 *
 * <p>流动水、含水方块、霜冰不计入四邻。世界生成（{@code WorldGenRegion}）不走
 * {@code Level#setBlock}，已存在的水也不回扫——只拦新写入。</p>
 *
 * <p>热路径：四邻已够数时直接放行，不做玩家查找（结果与先找玩家相同）。</p>
 */
public final class LimitedWaterRules {

    /** 纯水源 water8：{@code Blocks.WATER} 且 {@code LEVEL=0}（不含含水方块）。 */
    public static boolean isWaterSource(BlockState state) {
        return state.is(Blocks.WATER) && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /** 流动 water7：amount 7 非下落（legacy {@code LEVEL=1}）。 */
    public static BlockState flowingWater7() {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);
    }

    /** 四邻计数口径：纯源水或冰/浮冰/蓝冰；流动水、含水方块、霜冰不算。 */
    public static boolean countsAsNeighbour(BlockState state) {
        return isWaterSource(state)
                || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.BLUE_ICE);
    }

    /** 水平四邻（x±1、z±1）中计数方块的格数。 */
    public static int countNeighbours(LevelReader level, BlockPos pos) {
        int count = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (countsAsNeighbour(level.getBlockState(pos.relative(direction)))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 对 rotating 内一次源水写入给出改写态。调用方已确认：服务端、rotating、开关开、
     * 新态为纯水源。
     *
     * @return water7 表示改写；{@code null} 表示原样写入
     */
    @Nullable
    public static BlockState replacementFor(ServerLevel level, BlockPos pos) {
        int neighbours = countNeighbours(level, pos);
        if (neighbours >= LimitedWaterMath.MIN_NEIGHBOURS_TO_KEEP) {
            return null;
        }
        ServerPlayer decider = nearestDecider(level, pos);
        boolean deciderCreative = decider != null && decider.isCreative();
        return LimitedWaterMath.keepSource(neighbours, deciderCreative) ? null : flowingWater7();
    }

    /**
     * 决定者：以 {@code pos} 格中心为球心、半径内最近的非旁观玩家；无人返回 {@code null}。
     * 玩家与水格均先投影到真实世界坐标再计距。
     */
    @Nullable
    public static ServerPlayer nearestDecider(ServerLevel level, BlockPos pos) {
        if (level.players().isEmpty()) {
            return null;
        }
        Vec3 water = SableWorldPosition.project(level, pos.getCenter());
        ServerPlayer nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double distanceSqr = SableWorldPosition.project(level, player.position()).distanceToSqr(water);
            if (LimitedWaterMath.withinDeciderRadius(distanceSqr) && distanceSqr < nearestSqr) {
                nearest = player;
                nearestSqr = distanceSqr;
            }
        }
        return nearest;
    }

    private LimitedWaterRules() {
    }
}
