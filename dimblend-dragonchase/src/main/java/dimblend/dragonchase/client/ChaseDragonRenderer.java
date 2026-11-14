package dimblend.dragonchase.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.dragonchase.entity.ChaseDragon;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;

/**
 * Vanilla dragon visuals plus one healing beam per healing crystal.
 *
 * <p>The vanilla renderer only knows {@code EnderDragon.nearestCrystal} (one beam,
 * {@code E:\misc\mcsrc-1.21.1\net\minecraft\client\renderer\entity\EnderDragonRenderer.java:84-94});
 * the chase dragon has up to one crystal per crystal-car block and must show them all
 * (requirements.md §3.4 decision crystal-heal), so the ids arrive via {@link ClientCrystalBeams}
 * and the beams are drawn here with the vanilla beam geometry.
 */
public class ChaseDragonRenderer extends EnderDragonRenderer {
    public ChaseDragonRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(EnderDragon dragon, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
            int packedLight) {
        super.render(dragon, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        if (!(dragon instanceof ChaseDragon chase)) {
            return;
        }
        int[] crystalIds = ClientCrystalBeams.idsFor(chase.getId());
        if (crystalIds.length == 0) {
            return;
        }
        Level level = chase.level();
        if (level == null) {
            return;
        }
        float dragonX = (float) Mth.lerp((double) partialTick, chase.xo, chase.getX());
        float dragonY = (float) Mth.lerp((double) partialTick, chase.yo, chase.getY());
        float dragonZ = (float) Mth.lerp((double) partialTick, chase.zo, chase.getZ());
        for (int crystalId : crystalIds) {
            Entity entity = level.getEntity(crystalId);
            if (!(entity instanceof EndCrystal crystal)) {
                continue;
            }
            float dx = (float) (crystal.getX() - (double) dragonX);
            float dy = (float) (crystal.getY() - (double) dragonY);
            float dz = (float) (crystal.getZ() - (double) dragonZ);
            poseStack.pushPose();
            EnderDragonRenderer.renderCrystalBeams(dx, dy + EndCrystalRenderer.getY(crystal, partialTick), dz, partialTick,
                    chase.tickCount, poseStack, bufferSource, packedLight);
            poseStack.popPose();
        }
    }
}
