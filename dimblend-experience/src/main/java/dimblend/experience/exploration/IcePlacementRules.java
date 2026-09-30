package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.compat.create.DeployerPlayers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * G3 配套冰放置禁令（rotating 内）：冰/浮冰/蓝冰（不含霜冰）不能由
 * <ul>
 * <li>生存模式玩家放置（其它模式玩家放行）；</li>
 * <li>Create 机械手放置（无论附近玩家什么模式）。</li>
 * </ul>
 * 其它机器/假玩家不拦。
 *
 * <p>判定挂在 {@code BlockItem#place} 入口（见 {@code IcePlacementBanMixin}），双端执行：
 * 客户端同判拒绝可避免放置预测出幽灵方块。SERVER 配置经同步在客户端可读，读取前先
 * {@link Config#isLoaded()} 守卫。</p>
 */
public final class IcePlacementRules {

    public static boolean isBannedIce(Block block) {
        return block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE;
    }

    public static boolean shouldBlock(BlockItem item, BlockPlaceContext context) {
        if (!isBannedIce(item.getBlock())) {
            return false;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return false;
        }
        if (!Config.isLoaded() || !Config.ICE_PLACEMENT_BAN.get()) {
            return false;
        }
        if (!RotatingDimension.is(context.getLevel())) {
            return false;
        }
        if (DeployerPlayers.isDeployer(player)) {
            return true;
        }
        if (player instanceof FakePlayer) {
            return false;
        }
        return isSurvival(player);
    }

    /**
     * 是否生存模式：非创造、非旁观且可建造（游戏模式写入 abilities 时，冒险/旁观的
     * {@code mayBuild} 为 false）。只用双端都有的接口，客户端预判与服务端判定同口径。
     */
    static boolean isSurvival(Player player) {
        return !player.isCreative() && !player.isSpectator() && player.getAbilities().mayBuild;
    }

    private IcePlacementRules() {
    }
}
