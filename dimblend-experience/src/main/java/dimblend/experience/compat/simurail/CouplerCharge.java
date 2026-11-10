package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.api.util.ChunkAvailability;
import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * E10/E11 扳手充能：状态切换、自动取消充能的对账。
 *
 * <p><b>充能 = 解锁</b>。Simurail 的自动车钩「已通电」就是解锁态：
 * {@code sable$physicsTick} 在 {@code isPowered()} 为真时解除伙伴、并且不再
 * {@code findPartner}。本功能不改那套物理，只把扳手接到同一根杆上——
 * mixin 让 {@code isPowered()} 对充能态返回真，断钩、拒绝连挂全部沿用 Simurail
 * 既有路径（架构原则：复用既有框架，不绕过它另砌一套）。</p>
 *
 * <p>两种充能形式并存且互不干扰：红石信号（Simurail 原生，受 {@code couplerRedstone}
 * 开关约束）与扳手充能（本功能）。只有扳手充能发粒子——用户口径「此种类型的充能会使
 * 车钩散发红石粒子效果」。</p>
 */
public final class CouplerCharge {

    /**
     * 扳手右键：充能 ⇄ 取消充能。
     *
     * @return true = 已接管（调用方须返回 SUCCESS，挡掉 Simurail 原生的换类型动作）
     */
    public static boolean toggleCharge(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof AutomaticCouplerBlockEntity be)
                || !(be instanceof CouplerChargeAccess access)) {
            return false;
        }
        // The client acknowledges the click; only the server decides and synchronizes state.
        if (level.isClientSide()) return true;
        if (!access.dimblend$wrenchCharged()
                && (!net.neoforged.fml.ModList.get().isLoaded("dimblend")
                || !CoreRegions.isSurface(level, pos))) {
            return false;
        }
        access.dimblend$setWrenchCharged(!access.dimblend$wrenchCharged());
        be.setChanged();
        be.sendData();
        return true;
    }

    /**
     * 服务端对账（{@code lazyTick}，每 10 tick）：充能态且确实断钩、半径内又没有另一只
     * 车钩时取消充能。它已经不再隔开任何东西，留着只会在下次推车靠近时拒绝连挂。
     *
     * <p>伙伴所在区块未加载时整轮跳过——「未加载」不等于「已断开」，
     * 不能凭无法核验的状态把充能撤掉（同 Simurail 既有口径）。</p>
     *
     * @param partnerPos 可为 null；从 BE 的 {@code partnerPos} 传入，mixin 包外不碰该字段
     */
    public static void reconcile(Level level, AutomaticCouplerBlockEntity self,
            CouplerChargeAccess access, BlockPos partnerPos) {
        if (!access.dimblend$wrenchCharged()) {
            return;
        }
        if (partnerPos != null) {
            if (!ChunkAvailability.isChunkLoaded(level, partnerPos)) {
                return;
            }
            if (level.getBlockEntity(partnerPos) instanceof AutomaticCouplerBlockEntity) {
                return;
            }
        }
        if (!CouplerChargeMath.shouldDecharge(true, false, CouplerChargeScan.isAnotherCouplerNear(level, self))) {
            return;
        }
        access.dimblend$setWrenchCharged(false);
        self.setChanged();
        self.sendData();
    }

    private CouplerCharge() {
    }

    /** Loaded only behind the core-mod guard; the experience mod can run without core. */
    private static final class CoreRegions {
        private static boolean isSurface(Level level, BlockPos pos) {
            return dimblend.api.RegionLanes.isSurface(level,
                    dimblend.experience.compat.sable.SableWorldPosition.projectBlock(level, pos));
        }
    }
}
