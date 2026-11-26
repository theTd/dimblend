package dimblend.blocks.compat.create.funnel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;

import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 过滤器槽位定位，逐行对齐 Create 6.0.10 的
 * {@code com.simibubi.create.content.logistics.funnel.FunnelFilterSlotPositioning}，
 * 唯一差异是"传送带漏斗"的判定从 {@code instanceof BeltFunnelBlock} 放宽到
 * {@link #isBeltFunnel(BlockState)}（同时认我们自家的 {@link DumpBeltFunnelBlock}）。
 *
 * <p>为什么必须替换而不能直接复用原版：原版三个分支全部以
 * {@code state.getBlock() instanceof BeltFunnelBlock} / {@code instanceof FunnelBlock} 判型。
 * 我们的 {@link DumpBeltFunnelBlock} 继承 {@code AbstractHorizontalFunnelBlock}（既不是
 * {@code BeltFunnelBlock}，也不是 {@code FunnelBlock}），会被原版一路误判成"立式漏斗"：</p>
 * <ul>
 *   <li>{@code getLocalOffset} 落到非传送带水平分支 {@code voxelSpace(8, 12.2, 8.55)}——
 *       延展态本该在 {@code voxelSpace(8, 15.5, 13)}（随朝向旋转），实际把图标留在方块体内，
 *       于是"延展后图标不再可见"；</li>
 *   <li>{@code rotate} 落到最后的立式分支
 *       （{@code rotateY(horizontalAngle(facing)) + rotateX(±90)}），图标平面被立起来，
 *       于是"过滤器图标方向错误"；</li>
 *   <li>{@code isSideActive} 在延展态仍返回 {@code direction == facing}（正确应为 {@code UP}），
 *       槽位留在侧面而不是喷口顶面。</li>
 * </ul>
 *
 * <p>纯算术与分支判定都放在 {@link DumpFunnelLayout}，本类只负责把方块状态翻译成那次调用。</p>
 */
public class DumpFunnelFilterSlotPositioning extends ValueBoxTransform.Sided {

    /** 该状态是否应按传送带漏斗口径摆放图标（Create 的 {@code BeltFunnelBlock} + 我们的）。 */
    public static boolean isBeltFunnel(BlockState state) {
        return state.getBlock() instanceof BeltFunnelBlock || state.getBlock() instanceof DumpBeltFunnelBlock;
    }

    /** 传送带漏斗形态；非传送带漏斗返回 {@code null}。 */
    public static DumpFunnelLayout.Shape beltShapeOf(BlockState state) {
        if (state.getBlock() instanceof BeltFunnelBlock) {
            return DumpFunnelLayout.Shape.of(state.getValue(BeltFunnelBlock.SHAPE));
        }
        if (state.getBlock() instanceof DumpBeltFunnelBlock) {
            return DumpFunnelLayout.Shape.of(state.getValue(DumpBeltFunnelBlock.SHAPE));
        }
        return null;
    }

    @Override
    public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
        Direction funnelFacing = AbstractFunnelBlock.getFunnelFacing(state);
        if (funnelFacing == null) {
            return null;
        }
        return DumpFunnelLayout.slotOffset(getSide(), funnelFacing, beltShapeOf(state));
    }

    @Override
    public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
        Direction facing = AbstractFunnelBlock.getFunnelFacing(state);
        DumpFunnelLayout.SlotRotation mode = DumpFunnelLayout.slotRotation(facing, beltShapeOf(state),
                state.getBlock() instanceof FunnelBlock);
        switch (mode) {
            case VERTICAL, BELT_SIDED -> super.rotate(level, pos, state, ms);
            case BELT_SIDED_TILTED, FUNNEL_TILTED -> {
                super.rotate(level, pos, state, ms);
                TransformStack.of(ms).rotateXDegrees(-22.5f);
            }
            case FLATTENED -> {
                float yRot = AngleHelper.horizontalAngle(facing) + (facing == Direction.DOWN ? 180 : 0);
                ((PoseTransformStack) TransformStack.of(ms).rotateYDegrees(yRot))
                        .rotateXDegrees(facing == Direction.DOWN ? -90f : 90f);
            }
        }
    }

    @Override
    protected boolean isSideActive(BlockState state, Direction direction) {
        return DumpFunnelLayout.slotSideActive(direction, AbstractFunnelBlock.getFunnelFacing(state),
                beltShapeOf(state));
    }

    @Override
    protected Vec3 getSouthLocation() {
        return Vec3.ZERO;
    }
}
