package dimblend.experience.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.compat.sable.SableLinkedDiagnostics;
import net.minecraft.world.entity.player.Player;

public final class SableLinkedClientDiagnostics {

    public static String renderPoses(Player player, float partialTick) {
        StringBuilder result = new StringBuilder();
        Iterable<SubLevel> intersecting = Sable.HELPER.getAllIntersecting(
                player.level(), new BoundingBox3d(player.getBoundingBox().inflate(1.05D)));
        for (SubLevel subLevel : intersecting) {
            if (subLevel instanceof ClientSubLevel clientSubLevel) {
                result.append(" sub=").append(subLevel.getUniqueId())
                        .append("{render=")
                        .append(SableLinkedDiagnostics.pose(clientSubLevel.renderPose(partialTick)))
                        .append('}');
            }
        }
        return result.length() == 0 ? " render=[]" : result.toString();
    }

    private SableLinkedClientDiagnostics() {
    }
}
