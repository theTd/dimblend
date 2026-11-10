package dimblend.experience.chassis;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import dimblend.client.ClientBandLane;
import dimblend.client.ClientBandProgress;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.OakTrackCorridor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 客户端对服务端 {@code CorridorRails.isGeneratedTrack} 的 per-delegate 预测。
 *
 * <p>种子决定的完整 band layout 从不同步，客户端只有服务器推送的“玩家当前 band 的 lane”
 * 与 band 大小。因此这里只在<b>驾驶台与玩家处于同一 band region</b> 时用该 lane 反推
 * {@link ChassisLaneRule} 的轨道材质；跨 region、lane 未知或 band 大小未同步时返回 false
 * （不取消点击），让服务端决定。这样不会用过期 lane 去吞掉一个本应放行的放置。</p>
 *
 * <p>只在客户端 classpath 上加载；由 {@link ChassisSelection#applicable} 的 Dist.CLIENT 分支调用。
 * 服务端的 starter-depot 缺口判断依赖 generator 状态，客户端不复制；极端的缺口内人工放轨仍由
 * 服务端裁决，客户端只在 lane 匹配时取消。</p>
 */
public final class ChassisClientTrackRule {
    static boolean matches(BlockPos pos, BlockState below, Player player) {
        int bandSize = ClientBandProgress.bandSize();
        if (bandSize <= 0) {
            return false;
        }
        if (BandLayout.regionOfBlockX(pos.getX(), bandSize)
                != BandLayout.regionOfBlockX(player.getBlockX(), bandSize)) {
            return false;
        }
        ResourceLocation expected = ChassisLaneRule.trackId(ClientBandLane.lane());
        if (expected == null) {
            return false;
        }
        return below.getBlock() instanceof TrackBlock
                && below.getValue(TrackBlock.SHAPE) == TrackShape.XO
                && BuiltInRegistries.BLOCK.getKey(below.getBlock()).equals(expected)
                && pos.below().getY() == OakTrackCorridor.TRACK_Y
                && pos.getZ() == OakTrackCorridor.CORRIDOR_Z;
    }

    private ChassisClientTrackRule() {
    }
}
