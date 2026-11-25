package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock;

import dimblend.experience.compat.tavern.TapAttachmentRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 龙头只能挂在"酿造酒桶"的侧壁出酒口（Audit：酒桶专用龙头）。
 *
 * <p>两个口子：</p>
 * <ol>
 *   <li><b>放置/存活</b>：上游 {@code TapBlock} <b>没有</b>覆写 {@code canSurvive}
 *       （javap 核实：只有 {@code updateShape}），任何方块旁都能贴龙头。这里在 mixin 类体里
 *       合并一个 {@code canSurvive} 覆写（Mixin 允许新增实例方法，它会在目标类里覆盖
 *       {@code Block#canSurvive}，{@code BlockItem#place} 的虚调用因此生效）。判定
 *       {@link TapAttachmentRules}：背后必须是 {@code BarrelBlock} 且是侧壁正中出酒口、
 *       朝向一致。</li>
 *   <li><b>随桶消失</b>：{@code updateShape} 里只要自身已不合法就掉落（保留含水状态：
 *       返回水方块而不是空气），不依赖"后邻方块变化"这一具体 direction——
 *       多格酒桶逐格销毁时方向并不固定。</li>
 * </ol>
 *
 * <p>龙头方块本身带 {@code WATERLOGGED}/{@code OPEN}/{@code TRIGGERED}，只读不写。</p>
 */
@Mixin(value = TapBlock.class, remap = false)
public abstract class TapBlockMixin {

    /**
     * 合并进目标类的新方法：只有当背后是酒桶出酒口时才允许存在。
     *
     * <p>注意：本方法<b>不在</b> {@code TapBlock} 的声明里，是"新增覆盖"，因此不能用
     * {@code @Inject}，只能写进 mixin 类体（运行时它会覆盖 {@code Block#canSurvive}）。</p>
     */
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(TapBlock.FACING);
        BlockPos behindPos = pos.relative(facing.getOpposite());
        BlockState behind = level.getBlockState(behindPos);
        if (!(behind.getBlock() instanceof BarrelBlock)) {
            return false;
        }
        return TapAttachmentRules.isValidConnection(
                behind.getValue(BarrelBlock.LAYER),
                behind.getValue(BarrelBlock.INDEX),
                behind.getValue(BarrelBlock.FACING),
                facing);
    }

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private void dimblend$dropWhenDetached(BlockState state, Direction direction, BlockState neighborState,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos, CallbackInfoReturnable<BlockState> cir) {
        if (this.canSurvive(state, level, pos)) {
            return;
        }
        if (level instanceof Level realLevel && !realLevel.isClientSide) {
            realLevel.destroyBlock(pos, true);
        }
        cir.setReturnValue(state.getValue(TapBlock.WATERLOGGED)
                ? Blocks.WATER.defaultBlockState()
                : Blocks.AIR.defaultBlockState());
    }
}
