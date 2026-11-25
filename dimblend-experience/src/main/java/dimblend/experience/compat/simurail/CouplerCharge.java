package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.api.util.ChunkAvailability;
import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
 * <p>2026-11-25 口径变更：<b>取消「只允许在地面区域充能」的限制</b>，改为
 * <b>编组速度 &lt; 1 m/s 才能临时断钩</b>。充能本身（解锁态）随时可取消，
 * 只有「扳手 → 从未充能变为充能」这一步（= 临时断钩）受速度门控；速度达到
 * 1 m/s 及以上时整次交互静默失败（不摆臂、不播音、不发粒子）。两端各自算速度：
 * 服务端读子层级的刚体线速度，客户端读 {@code Sable.HELPER.getVelocity}，
 * 未组装的方块速度按 0 处理。</p>
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
        boolean charged = access.dimblend$wrenchCharged();
        // 两端同判：速度不达标时都返回 false，客户端不会摆臂/播音，服务端也不会改状态。
        if (!CouplerChargeMath.allowsWrenchToggle(charged, currentSpeed(level, pos))) {
            return false;
        }
        // The client acknowledges the click; only the server decides and synchronizes state.
        if (level.isClientSide()) return true;
        access.dimblend$setWrenchCharged(!charged);
        be.setChanged();
        be.sendData();
        return true;
    }

    /**
     * 车钩所在编组的速度（m/s）：服务端读刚体线速度，客户端读同步过来的速度。
     * 未组装（不在任何子层级内）按 0 处理——静止的车钩当然允许临时断钩。
     */
    public static double currentSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return Sable.HELPER.getVelocity(level, pos.getCenter()).length();
        }
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        if (!(subLevel instanceof ServerSubLevel server)) {
            return 0.0D;
        }
        RigidBodyHandle handle = RigidBodyHandle.of(server);
        return handle == null ? 0.0D : handle.getLinearVelocity().length();
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
}
