package dimblend.experience.compat.create.observer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerRenderer;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.mojang.math.Axis;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Draws the observer blaze: Create's stock keeper blaze (logistics hat, FADING heat so it is animated, head turned
 * towards the nearest player) and a slowly spinning spyglass half a block above the cage.
 *
 * <p>Two details matter for blocks on a Sable plot. The block entity lives at plot coordinates, so the camera
 * distance the vanilla dispatcher would use is meaningless -- {@link #shouldRender} therefore never culls. And the
 * head chases the player's position <em>inside the plot</em>, projected through the interpolated render pose, so a
 * vehicle driving past a player does not make the blaze stare at a plot-space ghost.</p>
 */
public final class ObserverBlazeRenderer implements BlockEntityRenderer<ObserverBlazeBlockEntity> {
    private static final ItemStack SPYGLASS = new ItemStack(Items.SPYGLASS);
    /** Half a block above the block's top face. */
    private static final float ITEM_Y = 1.5F;
    private static final float ITEM_SCALE = 0.5F;
    private static final float SPIN_DEGREES_PER_TICK = 1.5F;
    private static final float HEAD_TURN_PER_FRAME = 0.25F;

    public ObserverBlazeRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ObserverBlazeBlockEntity observer, float partialTick, PoseStack pose, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        Level level = observer.getLevel();
        if (level == null) {
            return;
        }
        chaseHead(observer);
        // Heat FADING, as Create renders a stock keeper: BLAZE_IDLE, animated, rods drawn, no flame quad.
        BlazeBurnerRenderer.renderShared(pose, null, buffer, level, observer.getBlockState(),
                BlazeBurnerBlock.HeatLevel.FADING, 0.0F, AngleHelper.rad(observer.headAngleDegrees()), true,
                observer.goggles(), AllPartialModels.LOGISTICS_HAT, observer.hashCode());
        renderSpyglass(observer, level, partialTick, pose, buffer, packedLight, packedOverlay);
    }

    /** Never cull by camera distance: the block entity sits in a Sable plot, not where it is drawn. */
    @Override
    public boolean shouldRender(ObserverBlazeBlockEntity observer, Vec3 cameraPos) {
        return true;
    }

    private static void chaseHead(ObserverBlazeBlockEntity observer) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isInvisible()) {
            return;
        }
        double x = player.getX();
        double z = player.getZ();
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(observer);
        if (subLevel != null) {
            Vector3d local = new Vector3d(x, player.getY(), z);
            subLevel.renderPose().transformPositionInverse(local);
            x = local.x;
            z = local.z;
        }
        BlockPos pos = observer.getBlockPos();
        double dx = x - (pos.getX() + 0.5);
        double dz = z - (pos.getZ() + 0.5);
        // Create's own formula (BlazeBurnerBlockEntity.tickAnimation): the head faces the player on the XZ plane.
        float target = AngleHelper.deg(-Mth.atan2(dz, dx)) - 90.0F;
        observer.chaseHeadAngle(target, HEAD_TURN_PER_FRAME);
    }

    private static void renderSpyglass(ObserverBlazeBlockEntity observer, Level level, float partialTick,
                                       PoseStack pose, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        float time = level.getGameTime() + partialTick;
        pose.pushPose();
        pose.translate(0.5F, ITEM_Y, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees((time * SPIN_DEGREES_PER_TICK) % 360.0F));
        pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
        Minecraft.getInstance().getItemRenderer().renderStatic(SPYGLASS, ItemDisplayContext.FIXED, packedLight,
                packedOverlay, pose, buffer, level, observer.hashCode());
        pose.popPose();
    }
}
