package dimblend.experience.compat.sable;

import javax.annotation.Nullable;

import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 结构空位拟合的爆炸射线穿透配套：让 {@link SableVoidFit} 写入世界空间的
 * {@code minecraft:structure_void} 在 {@code Explosion#explode()} 射线采样中表现为空气。
 *
 * <p>根因：原版 {@code Blocks.STRUCTURE_VOID} 注册时未声明 {@code .air()}
 * （{@code Blocks.java:4973}），{@code defaultBlockState().isAir()} 为 {@code false}；
 * 而 Sable 的 {@code ExplosionMixin#sable$redirectBlockExplosionResistance}
 * （{@code ExplosionMixin.java:95}）在世界空间 {@code !blockstate.isAir()} 时直接 {@code return}，
 * 根本不会把爆炸射线投射进任何 {@code SubLevel} plot，导致拟合启用后旋转维度内的所有
 * Sable 列车/载具对末影水晶、TNT、苦力怕等世界空间爆炸完全免疫（既不炸坏车厢方块也不受冲量），
 * 同时原版射线还会把这些 {@code structure_void} 当成 0 抗性非空气方块加入 {@code toBlow} 炸出漏水孔。</p>
 *
 * <p>做法：仅对服务端旋转维度内由 {@link VoidFitTracker} 跟踪（{@code targetCells} 或未拆完的
 * {@code materializedCells} 尾迹）的 {@code structure_void} 格在爆炸射线采样时返回空气：
 * 既放行 Sable {@code ExplosionMixin} 进入 {@code SubLevel} 局部空间结算方块抗性与刚体冲量，
 * 又避免原版射线误扣每步 {@code 0.09F} 衰减或把拟合防水壳炸穿；非拟合（如人工放置或 plot 空间）的
 * {@code structure_void} 原样保留。本类不引用任何 Sable 类型，Sable 缺席时安全空转。</p>
 */
public final class VoidFitExplosionPassthrough {

    public static boolean isTrackedFittedCell(@Nullable VoidFitTracker tracker, long cellLong) {
        return tracker != null && tracker.isFittedCell(cellLong);
    }

    public static BlockState maskFittedStructureVoid(Level level, BlockPos pos, BlockState state) {
        if (!state.is(Blocks.STRUCTURE_VOID) || !(level instanceof ServerLevel)) {
            return state;
        }
        if (!VoidFitTracker.hasAny()) {
            return state;
        }
        if (!Config.isLoaded() || !Config.SABLE_STRUCTURE_VOID_FIT.get() || !RotatingDimension.is(level)) {
            return state;
        }
        if (isTrackedFittedCell(VoidFitTracker.peek(level.dimension()), pos.asLong())) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    private VoidFitExplosionPassthrough() {
    }
}
