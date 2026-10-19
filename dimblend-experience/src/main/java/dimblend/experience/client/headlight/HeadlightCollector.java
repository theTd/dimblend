package dimblend.experience.client.headlight;

import dev.ryanhcode.sable.Sable;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Transforms plot-local lamps with the exact interpolated pose used for vehicle geometry. */
public final class HeadlightCollector {
    public static List<HeadlightBeam> collect(ClientLevel level, Vec3 camera, Frustum frustum, double range) {
        List<HeadlightBeam> beams = new ArrayList<>();
        for (var entry : FrontlightIndex.reconcile(level).entrySet()) {
            for (var pos : entry.getValue()) {
                var state = entry.getKey().getBlockState(pos);
                if (!FrontlightIndex.isFrontlight(state) || !state.getValue(BlockStateProperties.LIT)) continue;
                var sub = Sable.HELPER.getContainingClient(pos);
                if (sub == null) continue;
                var facing = state.getValue(BlockStateProperties.FACING);
                Vec3 localDirection = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
                boolean noBase = state.getProperties().stream()
                        .filter(property -> property.getName().equals("no_base"))
                        .anyMatch(property -> Boolean.TRUE.equals(state.getValue(property)));
                var pose = sub.renderPose();
                Vec3 lens = pose.transformPosition(HeadlightMath.lens(pos.getCenter(), localDirection, noBase));
                // A range sphere bounds the entire finite cone, including points behind the camera.
                if (lens.distanceToSqr(camera) > Math.pow(range + Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0, 2)) continue;
                if (frustum != null && !frustum.isVisible(new AABB(lens, lens).inflate(range))) continue;
                Vector3d rotated = pose.orientation().transform(new Vector3d(localDirection.x, localDirection.y, localDirection.z));
                beams.add(new HeadlightBeam(lens, new Vec3(rotated.x, rotated.y, rotated.z).normalize()));
            }
        }
        return HeadlightMath.nearest(beams, camera);
    }

    private HeadlightCollector() {}
}
