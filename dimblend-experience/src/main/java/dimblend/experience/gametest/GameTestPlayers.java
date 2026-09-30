package dimblend.experience.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * GameTest 用的 {@link ServerPlayer} 替身：按给定游戏模式覆写 {@code isCreative}/{@code isSpectator}
 * 并按模式设置 abilities（冒险/旁观 {@code mayBuild=false}），直接加入 {@code ServerLevel#players()}
 * 供「按游戏模式找最近玩家」读取。
 *
 * <p>不走 {@code PlayerList#placeNewPlayer}：入场会触发数据包同步，Sable 的
 * {@code OnDatapackSync} 监听向内嵌连接发 {@code sable:dimension_physics}，NeoForge 以
 * 「未协商通道」拒发并抛错。替身不入实体管理器、不被 tick，用例须在 finally 中
 * {@link #removeAll} 从玩家表移除。</p>
 */
public final class GameTestPlayers {

    /** 构造替身但不加入玩家表（只作为放置上下文的玩家时用）。 */
    public static ServerPlayer create(GameTestHelper helper, GameType mode, Vec3 position) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "dbx-" + mode.getName()), ClientInformation.createDefault()) {
            @Override
            public boolean isCreative() {
                return mode.isCreative();
            }

            @Override
            public boolean isSpectator() {
                return mode == GameType.SPECTATOR;
            }
        };
        mode.updatePlayerAbilities(player.getAbilities());
        // 不用 moveTo：ServerPlayer 版会调 connection.resetPosition()，替身没有连接
        player.setPos(position.x, position.y, position.z);
        return player;
    }

    /** 构造替身并加入 {@code ServerLevel#players()}。 */
    public static ServerPlayer spawn(GameTestHelper helper, GameType mode, Vec3 position) {
        ServerPlayer player = create(helper, mode, position);
        helper.getLevel().players().add(player);
        return player;
    }

    public static void removeAll(GameTestHelper helper, List<ServerPlayer> players) {
        helper.getLevel().players().removeAll(players);
        players.clear();
    }

    private GameTestPlayers() {
    }
}
