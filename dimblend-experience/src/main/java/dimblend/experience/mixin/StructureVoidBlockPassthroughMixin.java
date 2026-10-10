package dimblend.experience.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StructureVoidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 结构空位拟合的玩家侧兜底（G3 配套）：旋转维度内 structure_void 对交互射线透明。
 *
 * <p>问题：原版 {@code StructureVoidBlock#getShape} 返回格心 6×6×6 小盒，而一切交互射线
 * 走 {@code ClipContext.Block.OUTLINE}（即 {@code BlockStateBase::getShape}，见
 * ClipContext.java:53）——挖方块/近战/右键/桶的 POV 射线都会被这个小盒拦下。拟合空位
 * 为保水密按保守覆盖取格，必然凸出载具可见几何（格粒度表达不了亚格表面），凸出部分
 * 就在"看起来什么都没有"的空气里制造不可交互的隐形点；几何凸出无法用摆位消除
 * （消除即漏水），只能让空位对射线无害。</p>
 *
 * <p>做法：旋转维度内把 {@code getShape} 掏空。穿透后客户端 pick 命中真实目标
 * （{@code GameRenderer#pick} 先方块后实体，实体选取不再被空位截断），服务端攻击校验
 * 是 AABB 距离判定不重射线（ServerGamePacketListenerImpl#handleInteract），桶等服务端
 * 重射线同样走本方法一并穿透。挡水完全不受影响：{@code FlowingFluid#canHoldFluid}
 * （FlowingFluid.java:408）按方块 id 排除 structure_void，与形状无关。</p>
 *
 * <p>副作用面（已逐项核实）：{@code getOcclusionShape} 默认委派 {@code getShape}
 * （BlockBehaviour.java:270），但 6/16 小盒本就非满方块，掏空后面剔除/AO 判定结果不变；
 * {@code getVisualShape}/{@code getBlockSupportShape} 委派碰撞形状（本方法不改）；
 * 命中描边随之消失（原本玩家偶尔能看到一个莫名其妙的小盒描边，属预期收益）。
 * 非 {@code Level} 的包装 BlockGetter（如 RenderChunkRegion）落到原版形状，行为同前。</p>
 *
 * <p>门控与拟合调度同口径（配置已加载 + 开关开 + 旋转维度），维度外保留原版可
 * 选中/可见语义（结构方块 workflow 不受牵连）。目标为原版类、无条件加载（不引用
 * Sable 类型；Sable 缺席时旋转维度内本就没有拟合空位，门控自然空转）。</p>
 */
@Mixin(StructureVoidBlock.class)
public abstract class StructureVoidBlockPassthroughMixin {

    @Inject(
            method = "getShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"),
            cancellable = true)
    private void dimblend$passthroughInRotating(BlockState state, BlockGetter getter, BlockPos pos,
            CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (!(getter instanceof Level level)) {
            return;
        }
        if (!Config.isLoaded() || !Config.SABLE_STRUCTURE_VOID_FIT.get() || !RotatingDimension.is(level)) {
            return;
        }
        cir.setReturnValue(Shapes.empty());
    }
}
