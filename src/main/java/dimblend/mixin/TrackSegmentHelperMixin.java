package dimblend.mixin;

import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.track.TrackPropagator;
import dimblend.compat.CreateTrackGraphCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * DISABLED (unregistered in dimblend.mixins.json): the loadedChunksOnly proxy is a
 * LevelAccessor JDK proxy, not a Level. Create resolves every DiscoveredLocation's
 * dimension via `world instanceof Level` (ITrackBlock.lambda$getConnected$1) and falls
 * back to Level.OVERWORLD otherwise, so every node this redirect registers lands in the
 * overworld dimension and the sub-level track never enters the graph — trains cannot
 * attach at all. The original main-thread park problem this mixin targeted is instead
 * addressed by making the corridor stitch (CreateTrackGraphCompat) actually register the
 * track on chunk load, so simurail's force-register fallback rarely fires. If this
 * approach is revived, the proxy must be replaced by something Create sees as a Level.
 *
 * ---- original note ----
 * simurail 的 findBlockTrackSegment 在 getGraphLocationAt 为 null 时，把轨道块
 * 强制注册进 Create 图（onRailAdded 全网 walk）。子关卡轨道网未入图时该回退每
 * 0.1s 重跑一次，walk 前沿每遇未加载区块就在主线程同步 load（spark 实测 61.3%
 * server thread park）。用 loadedChunksOnly 代理替换传入的 Level：未加载区块
 * 读作 AIR，walk 自然断头，同时保留已加载区的正常入图。返回值本就被调用方 pop
 * 丢弃，语义无损。simurail 缺席或内部字节码漂移时本 mixin 静默跳过（@Pseudo +
 * require = 0），代价仅是回到未优化性能，不崩服。
 */
@Pseudo
@Mixin(targets = "com.crystaelix.simurail.content.track.TrackSegmentHelper", remap = false)
public abstract class TrackSegmentHelperMixin {

    @Redirect(
            method = "findBlockTrackSegment",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/track/TrackPropagator;"
                            + "onRailAdded(Lnet/minecraft/world/level/LevelAccessor;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)"
                            + "Lcom/simibubi/create/content/trains/graph/TrackGraph;"
            ),
            remap = false,
            require = 0
    )
    private static TrackGraph dimblend$onRailAddedLoadedOnly(
            LevelAccessor level, BlockPos pos, BlockState state
    ) {
        if (level instanceof ServerLevel serverLevel) {
            return TrackPropagator.onRailAdded(CreateTrackGraphCompat.loadedChunksOnly(serverLevel), pos, state);
        }
        return TrackPropagator.onRailAdded(level, pos, state);
    }
}
