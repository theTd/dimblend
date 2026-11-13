package dimblend.experience.mixin.compat.sable;

import dev.ryanhcode.sable.SableCommonEvents;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dimblend.experience.compat.sable.VoidFitPhysicsFilter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 短路世界区块（非 Sable plot）上空气与 {@code minecraft:structure_void} 互转时的无意义物理更新：
 *
 * <p>{@code SableVoidFit} 行驶拟合每轮批量将空气与 {@code structure_void} 互换。{@code LevelChunk#setBlockState}
 * 每次变更都会调用 {@link SableCommonEvents#handleBlockChange}，对世界区块最终驱动 7 次
 * {@code Rapier3D.changeBlock} JNI 调用（本格 + 六邻格重烘焙）及附近刚体唤醒。空气与无流体
 * {@code structure_void} 在 {@code RapierVoxelColliderBakery} 与 {@code VoxelNeighborhoodState} 中
 * 完全等价（均无实体碰撞箱、均非流体浮力体素、均无物理材质回调），跳过可消除大量无效 JNI 往返；
 * 水/熔岩与 {@code structure_void} 的互转及 plot 区块内的变更均保持原样放行。</p>
 */
@Mixin(value = SableCommonEvents.class, remap = false)
public abstract class SableVoidFitBlockChangeMixin {

    @Inject(
            method = "handleBlockChange(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;IIILnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1)
    private static void dimblend$skipWorldAirStructureVoidPhysics(
            ServerLevel level,
            LevelChunk chunk,
            int x,
            int y,
            int z,
            BlockState oldState,
            BlockState newState,
            CallbackInfo ci) {
        if (!VoidFitPhysicsFilter.isPhysicsEquivalentAirOrVoid(oldState, newState)) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && container.getChunkHolder(chunk.getPos()) == null) {
            ci.cancel();
        }
    }
}
