package dimblend.experience.compat.camerapture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.chrr.camerapture.item.PictureItem;
import me.chrr.camerapture.picture.ClientPictureStore;
import me.chrr.camerapture.picture.RemotePicture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;

/** Reuses Camerapture's cache/download lifecycle and draws on the native map plane. */
public final class FramedPictureRenderer {
    public static boolean isPicture(ItemStack stack) {
        return stack.getItem() instanceof PictureItem;
    }

    public static boolean render(ItemStack stack, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        var data = PictureItem.getPictureData(stack);
        if (data == null) {
            return false;
        }
        var picture = ClientPictureStore.getInstance().getServerPicture(data.id());
        if (picture.getStatus() != RemotePicture.Status.SUCCESS || picture.getWidth() <= 0 || picture.getHeight() <= 0) {
            return false;
        }
        float width = Math.min(1.0F, (float) picture.getWidth() / picture.getHeight());
        float height = Math.min(1.0F, (float) picture.getHeight() / picture.getWidth());
        poses.pushPose();
        // ItemFrameRenderer has already applied the ordinary item scale of 0.5.
        poses.scale(2.0F, 2.0F, 2.0F);
        poses.translate(0.0F, 0.0F, -1.0F / 128.0F);
        var pose = poses.last();
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(picture.getTextureIdentifier()));
        // The front of a native frame faces local -Z, so texture-left is local +X.
        vertices.addVertex(pose.pose(), width / 2, -height / 2, 0).setColor(-1).setUv(0, 1)
                .setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, -1);
        vertices.addVertex(pose.pose(), width / 2, height / 2, 0).setColor(-1).setUv(0, 0)
                .setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, -1);
        vertices.addVertex(pose.pose(), -width / 2, height / 2, 0).setColor(-1).setUv(1, 0)
                .setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, -1);
        vertices.addVertex(pose.pose(), -width / 2, -height / 2, 0).setColor(-1).setUv(1, 1)
                .setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, -1);
        poses.popPose();
        return true;
    }

    private FramedPictureRenderer() { }
}
