package dimblend.experience.client.headlight;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dimblend.experience.compat.dndecor.FrontlightSpotlight;
import dimblend.experience.headlight.HeadlightMath;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Collects activated world lamps and transforms vehicle lamps with their interpolated geometry pose. */
public final class HeadlightCollector {
    public static List<HeadlightBeam> collect(ClientLevel level, Vec3 camera, Frustum frustum, double range) {
        List<HeadlightBeam> beams = new ArrayList<>();
        for (var entry : FrontlightIndex.reconcile(level, camera).entrySet()) {
            for (var pos : entry.getValue()) {
                var state = entry.getKey().getBlockState(pos);
                if (!FrontlightSpotlight.isEnabled(state) || !state.getValue(BlockStateProperties.LIT)) continue;
                var sub = Sable.HELPER.getContainingClient(pos);
                var facing = state.getValue(BlockStateProperties.FACING);
                Vec3 localDirection = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
                boolean noBase = FrontlightSpotlight.hasNoBase(state);
                Vec3 localLens = HeadlightMath.lens(pos.getCenter(), localDirection, noBase);
                Vec3 lens = sub == null ? localLens : sub.renderPose().transformPosition(localLens);
                // A range sphere bounds the entire finite cone, including points behind the camera.
                if (lens.distanceToSqr(camera) > Math.pow(range + Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0, 2)) continue;
                if (frustum != null && !frustum.isVisible(new AABB(lens, lens).inflate(range))) continue;
                Vector3d rotated = new Vector3d(localDirection.x, localDirection.y, localDirection.z);
                if (sub != null) sub.renderPose().orientation().transform(rotated);
                beams.add(new HeadlightBeam(lens, new Vec3(rotated.x, rotated.y, rotated.z).normalize(),
                        sub instanceof ClientSubLevel client ? client : null));
            }
        }
        return HeadlightMath.nearest(beams, camera);
    }

    private HeadlightCollector() {}
}
