package dimblend.experience.compat.sable;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 世界区块（非 Sable plot）方块变更的 Rapier 物理更新短路判据：
 *
 * <p>{@code SableCommonEvents#handleBlockChange} 在世界区块上只服务于
 * {@code SubLevelPhysicsSystem#handleBlockChange}（更新本格及六邻格的 Rapier 体素碰撞体并唤醒附近刚体）。
 * 对 {@code RapierVoxelColliderBakery} 与 {@code VoxelNeighborhoodState} 而言，无流体空气
 * （{@code AIR} / {@code CAVE_AIR} / {@code VOID_AIR}）与 {@code minecraft:structure_void} 完全等价：
 * <ul>
 * <li>{@code FluidState#isEmpty()} 均为真——水与熔岩虽无原版方块碰撞箱，但在 Rapier 中会烘焙为
 *     {@code liquid=true} 的 1×1×1 浮力体素碰撞体（{@code RapierVoxelColliderBakery.java:56,64-67}），
 *     涉及流体的互转绝不可短路；</li>
 * <li>{@code getCollisionShape} 均为空，{@code isCollisionShapeFullBlock} 均为假，无物理属性回调。</li>
 * </ul>
 * 因此世界区块内二者互转时，本格与六邻格的烘焙结果恒不变，可安全跳过 7 次 {@code Rapier3D.changeBlock}
 * JNI 调用与刚体唤醒。plot 区块（{@code getChunkHolder != null}）仍需维护子层级包围盒、热力图与
 * 浮块控制器，由调用侧门控放行。</p>
 */
public final class VoidFitPhysicsFilter {

    public static boolean isPhysicsEquivalentAirOrVoid(BlockState oldState, BlockState newState) {
        return isPhysicsEquivalentTransition(
                oldState.isAir(), oldState.is(Blocks.STRUCTURE_VOID), oldState.getFluidState().isEmpty(),
                newState.isAir(), newState.is(Blocks.STRUCTURE_VOID), newState.getFluidState().isEmpty());
    }

    static boolean isPhysicsEquivalentTransition(
            boolean oldAir, boolean oldStructureVoid, boolean oldFluidEmpty,
            boolean newAir, boolean newStructureVoid, boolean newFluidEmpty) {
        return isAirOrDryStructureVoid(oldAir, oldStructureVoid, oldFluidEmpty)
                && isAirOrDryStructureVoid(newAir, newStructureVoid, newFluidEmpty);
    }

    private static boolean isAirOrDryStructureVoid(boolean isAir, boolean isStructureVoid, boolean fluidEmpty) {
        return (isAir || isStructureVoid) && fluidEmpty;
    }

    private VoidFitPhysicsFilter() {
    }
}
