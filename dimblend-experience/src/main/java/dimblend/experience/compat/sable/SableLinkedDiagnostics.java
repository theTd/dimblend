package dimblend.experience.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.player.Player;

public final class SableLinkedDiagnostics {

    public static String describe(Player player) {
        StringBuilder result = new StringBuilder();
        Iterable<SubLevel> intersecting = Sable.HELPER.getAllIntersecting(
                player.level(), new BoundingBox3d(player.getBoundingBox().inflate(1.05D)));
        for (SubLevel subLevel : intersecting) {
            result.append(" sub=").append(subLevel.getUniqueId())
                    .append("{last=").append(pose(subLevel.lastPose()))
                    .append(",logical=").append(pose(subLevel.logicalPose()))
                    .append('}');
        }
        return result.length() == 0 ? " sub=[]" : result.toString();
    }

    public static String pose(Pose3dc pose) {
        return "p" + pose.position() + ",r" + pose.orientation();
    }

    private SableLinkedDiagnostics() {
    }
}
