package dimblend.experience.client.dolls;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dimblend.experience.compat.dolls.DollKeeperSeatBlock;
import dimblend.experience.compat.dolls.DollKeeperSeatBlockEntity;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Reuses the saved doll's existing inventory model and texture; no new art or entity. */
public final class DollKeeperSeatRenderer implements BlockEntityRenderer<DollKeeperSeatBlockEntity> {
    private final ItemRenderer items;
    // Weak keys also release baked models after a resource reload.
    private final Map<BakedModel, Float> modelFloors = new WeakHashMap<>();

    public DollKeeperSeatRenderer(BlockEntityRendererProvider.Context context) {
        items = context.getItemRenderer();
    }

    @Override
    public void render(DollKeeperSeatBlockEntity keeper, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay) {
        ItemStack doll = keeper.doll();
        if (doll.isEmpty()) {
            return;
        }
        BakedModel model = items.getModel(doll, keeper.getLevel(), null, 0);
        float floor = modelFloors.computeIfAbsent(model, DollKeeperSeatRenderer::modelFloor);
        Direction facing = keeper.getBlockState().getValue(DollKeeperSeatBlock.FACING);
        pose.pushPose();
        // Create's cushion ends at y=8/16. ItemRenderer subsequently subtracts (.5,.5,.5).
        pose.translate(.5, 1 - floor, .5);
        // The upstream doll blockstates use north=0, east=90, south=180, west=270 (JSON rotation).
        pose.mulPose(Axis.YP.rotationDegrees(180 - facing.toYRot()));
        items.render(doll, ItemDisplayContext.NONE, false, pose, buffers, light, overlay, model);
        pose.popPose();
    }

    private static float modelFloor(BakedModel model) {
        float floor = Float.POSITIVE_INFINITY;
        for (int face = 0; face <= Direction.values().length; face++) {
            Direction side = face == Direction.values().length ? null : Direction.values()[face];
            for (BakedQuad quad : model.getQuads(null, side, RandomSource.create(42))) {
                int[] vertices = quad.getVertices();
                int stride = vertices.length / 4;
                for (int vertex = 0; vertex < 4; vertex++) {
                    floor = Math.min(floor, Float.intBitsToFloat(vertices[vertex * stride + 1]));
                }
            }
        }
        return Float.isFinite(floor) ? floor : 0;
    }
}
