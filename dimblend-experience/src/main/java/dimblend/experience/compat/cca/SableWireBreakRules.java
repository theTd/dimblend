package dimblend.experience.compat.cca;

import java.util.UUID;

import com.mrh0.createaddition.energy.IWireNode;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * 断钩后的跨载具电线反扫（2026-11-25 用户口径）。
 *
 * <p>两台载具被车钩直接耦合时允许互连电线；一旦车钩脱开（{@code removePartner}），
 * 这些线就不再合法。这里遍历两端子层级上的所有 {@link IWireNode}，把"另一端已不在
 * 同一布线域"的连接交给 Create: Addition 自己的 {@code IWireNode.disconnect} 断开
 * （沿用它删节点、掉线轴、唤醒电网的既有行为），不手写状态变更。</p>
 *
 * <p>只在服务端跑，且每次断开只在断点处播一次 {@code CHAIN_BREAK}，避免一条线上
 * 两侧节点各响一次。</p>
 */
public final class SableWireBreakRules {

    /** 车钩脱开后，清理两端载具之间不再合法的全部跨域连线。 */
    public static void breakCrossWires(Level level, SubLevel first, SubLevel second) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        if (first == null || second == null || first == second) {
            return;
        }
        UUID firstId = first.getUniqueId();
        UUID secondId = second.getUniqueId();
        int broken = 0;
        broken += sweep(level, first, firstId, secondId);
        broken += sweep(level, second, secondId, firstId);
        if (broken > 0) {
            Vec3 at = new Vec3(first.getPlot().getCenterBlock().getX() + 0.5,
                    first.getPlot().getCenterBlock().getY() + 0.5,
                    first.getPlot().getCenterBlock().getZ() + 0.5);
            level.playSound(null, BlockPos.containing(at), SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS,
                    1.0F, 0.8F);
        }
    }

    private static int sweep(Level level, SubLevel subLevel, UUID ownId, UUID otherId) {
        int broken = 0;
        for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof IWireNode node)) {
                continue;
            }
            BlockPos selfPos = node.getPos();
            for (int i = 0; i < node.getNodeCount(); i++) {
                if (!node.hasConnection(i)) {
                    continue;
                }
                BlockPos otherPos = node.getNodePos(i);
                if (otherPos == null) {
                    continue;
                }
                UUID otherNodeId = SableCoupledWireRules.subLevelId(level, otherPos);
                if (!SableCoupledWireRules.mustBreakAfterUncouple(ownId, otherNodeId)) {
                    continue;
                }
                IWireNode.disconnect(level, selfPos, otherPos);
                broken++;
            }
        }
        return broken;
    }

    /** 只在 Sable + Create: Addition + Simurail 同时在场的门控下调用。 */
    public static boolean available() {
        return ModList.get().isLoaded("sable") && ModList.get().isLoaded("createaddition");
    }

    private SableWireBreakRules() {
    }
}
