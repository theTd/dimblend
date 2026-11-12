package dimblend.experience.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlockEntity;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only renderer for centered 2-wide single-cell Create sliding doors ({@code dimblend_paired=true}).
 * Renders both left and right leaves shifted by {@code +-0.5} along the doorway axis so the pair spans
 * {@code [-0.5, +1.5]} around the single logical block cell.
 */
public final class SlidingDoorPairedRenderer {
    private SlidingDoorPairedRenderer() {
    }

    public static void renderPaired(
            SlidingDoorBlockEntity be,
            float partialTicks,
            PoseStack ms,
            MultiBufferSource buffer,
            int light,
            int overlay) {
        BlockState blockState = be.getBlockState();
        if (!SlidingDoorPairing.isPaired(blockState)) {
            return;
        }

        float value = be instanceof SlidingDoorAnimationAccess animAccess
                ? animAccess.dimblend$animationValue(partialTicks)
                : (blockState.getValue(DoorBlock.OPEN) ? 1.0f : 0.0f);

        Direction facing = blockState.getValue(DoorBlock.FACING);
        Direction leftDir = facing.getCounterClockWise();
        Direction rightDir = facing.getClockWise();
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        renderLeaf(blockState, DoorHingeSide.LEFT, leftDir, value, ms, vb, light);
        renderLeaf(blockState, DoorHingeSide.RIGHT, rightDir, value, ms, vb, light);
    }

    private static void renderLeaf(
            BlockState pairedState,
            DoorHingeSide leafHinge,
            Direction outwardDir,
            float value,
            PoseStack ms,
            VertexConsumer vb,
            int light) {
        BlockState leafState = pairedState
                .setValue(SlidingDoorPairing.PAIRED, false)
                .setValue(SlidingDoorBlock.VISIBLE, false)
                .setValue(DoorBlock.HINGE, leafHinge);

        Direction facing = leafState.getValue(DoorBlock.FACING);
        Direction movementDirection = facing.getClockWise();
        if (leafHinge == DoorHingeSide.LEFT) {
            movementDirection = movementDirection.getOpposite();
        }

        float value2 = Mth.clamp(value * 10.0f, 0.0f, 1.0f);
        Vec3 offset = Vec3.atLowerCornerOf(movementDirection.getNormal())
                .scale(value * value * 13.0f / 16.0f)
                .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(value2 * 1.0f / 32.0f));

        ms.pushPose();
        ms.translate(0.5f * outwardDir.getStepX(), 0.0f, 0.5f * outwardDir.getStepZ());
        try {
            if (((SlidingDoorBlock) leafState.getBlock()).isFoldingDoor()) {
                Couple<PartialModel> partials =
                        AllPartialModels.FOLDING_DOORS.get(BuiltInRegistries.BLOCK.getKey(leafState.getBlock()));
                if (partials == null) {
                    return;
                }
                boolean flip = leafHinge == DoorHingeSide.RIGHT;
                for (boolean left : Iterate.trueAndFalse) {
                    SuperByteBuffer partial = CachedBuffers.partial(partials.get(left ^ flip), leafState);
                    float f = flip ? -1.0f : 1.0f;

                    partial.translate(0.0f, -1.0f / 512.0f, 0.0f)
                            .translate(Vec3.atLowerCornerOf(facing.getNormal()).scale(value2 * 1.0f / 32.0f));
                    partial.rotateCentered(
                            Mth.DEG_TO_RAD * AngleHelper.horizontalAngle(facing.getClockWise()),
                            Direction.UP);

                    if (flip) {
                        partial.translate(0.0f, 0.0f, 1.0f);
                    }
                    partial.rotateYDegrees(91.0f * f * value * value);

                    if (!left) {
                        partial.translate(0.0f, 0.0f, f / 2.0f)
                                .rotateYDegrees(-181.0f * f * value * value);
                    }

                    if (flip) {
                        partial.translate(0.0f, 0.0f, -1.0f / 2.0f);
                    }

                    partial.light(light).renderInto(ms, vb);
                }
                return;
            }

            for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                CachedBuffers.block(leafState.setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, half))
                        .translate(0.0f, half == DoubleBlockHalf.UPPER ? 1.0f - 1.0f / 512.0f : 0.0f, 0.0f)
                        .translate(offset)
                        .light(light)
                        .renderInto(ms, vb);
            }
        } finally {
            ms.popPose();
        }
    }
}
