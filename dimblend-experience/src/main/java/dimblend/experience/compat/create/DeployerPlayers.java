package dimblend.experience.compat.create;

import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Create 机械手（Deployer）的假玩家识别。机械手放方块走 {@code ItemStack#useOn}，
 * 上下文玩家是 {@link DeployerFakePlayer}（Create 6.0.10 {@code DeployerHandler} 核实：
 * 点空气时不发 {@code RightClickBlock} 事件，故只能在放置层识别）。
 *
 * <p>Create 对本模组是可选依赖：对 {@link DeployerFakePlayer} 的 instanceof 只在
 * {@code isLoaded("create")} 守卫通过后执行，JVM 惰性解析，缺 Create 时不会加载其类型。</p>
 */
public final class DeployerPlayers {

    public static boolean isDeployer(Player player) {
        return player instanceof FakePlayer
                && ModList.get().isLoaded("create")
                && player instanceof DeployerFakePlayer;
    }

    private DeployerPlayers() {
    }
}
