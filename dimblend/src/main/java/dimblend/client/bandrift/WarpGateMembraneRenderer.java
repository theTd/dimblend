package dimblend.client.bandrift;

import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.block.WarpGateBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import net.minecraft.world.phys.Vec3;

/**
 * Replacement block-entity renderer for the warp gate column.
 *
 * <p>In {@link BandRiftMode#RIFT} and {@link BandRiftMode#MEMBRANE} this renderer draws
 * <b>nothing</b> — neither in {@code render} nor in the culling predicate — so the 293 gateway
 * draws per boundary column disappear entirely. Both modes draw the seam themselves
 * ({@link BandRiftRenderer}: volumetric mask or one batched membrane), never 293 block-entity
 * cubes.</p>
 *
 * <p>In {@link BandRiftMode#LEGACY} everything is handed to the vanilla renderer, including the
 * view-distance predicate, which is how the pre-PoC client looked. The vanilla instance is
 * allocated up front but only ever touched on the legacy path.</p>
 */
public final class WarpGateMembraneRenderer implements BlockEntityRenderer<WarpGateBlockEntity> {
    private final TheEndGatewayRenderer legacy;

    public WarpGateMembraneRenderer(BlockEntityRendererProvider.Context context) {
        this.legacy = new TheEndGatewayRenderer(context);
    }

    @Override
    public void render(WarpGateBlockEntity gate, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (BandRiftMode.current() == BandRiftMode.LEGACY) {
            this.legacy.render(gate, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        }
    }

    @Override
    public boolean shouldRender(WarpGateBlockEntity gate, Vec3 cameraPos) {
        return BandRiftMode.current() == BandRiftMode.LEGACY && this.legacy.shouldRender(gate, cameraPos);
    }

    @Override
    public boolean shouldRenderOffScreen(WarpGateBlockEntity gate) {
        return BandRiftMode.current() == BandRiftMode.LEGACY && this.legacy.shouldRenderOffScreen(gate);
    }
}
