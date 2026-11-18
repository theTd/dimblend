package dimblend.experience.compat.sophisticated;

import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.util.thread.SidedThreadGroups;

/**
 * SophisticatedCore {@code RecipeHelper} 的客户端 level 兜底。
 *
 * <p>{@code RecipeHelper} 每侧只缓存一个 {@code WeakReference<Level>}，唯一写入点是
 * {@code LevelEvent.Load} 且维度为 overworld 的那一路（{@code SophisticatedCore.java:98-103}）。
 * 在 {@code TrainTripWorld} 里玩家常驻 {@code dimblend:rotating}：客户端从未加载 overworld，
 * 该引用长期为空（或切世界后已被 GC），切石机/锻造升级的配方查询因此静默返回空列表。</p>
 *
 * <p>本类只在客户端线程给出 {@code Minecraft.getInstance().level}；专用服务器、集成服务器
 * 线程、以及早期无屏幕的客户端一律返回 {@code null}，不污染服务端侧缓存。</p>
 */
public final class SophisticatedClientLevels {

    private SophisticatedClientLevels() {
    }

    /** 客户端线程的当前世界；其他线程/侧返回 {@code null}。 */
    public static Level clientLevelForRecipeLookup() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return null;
        }
        if (Thread.currentThread().getThreadGroup() == SidedThreadGroups.SERVER) {
            return null;
        }
        return Client.currentLevel();
    }

    /** 单独成类：专用服务器上不会加载，避免解析客户端类。 */
    private static final class Client {

        private Client() {
        }

        private static Level currentLevel() {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            return minecraft == null ? null : minecraft.level;
        }
    }
}
