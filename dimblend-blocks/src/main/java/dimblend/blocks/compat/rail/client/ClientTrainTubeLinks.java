package dimblend.blocks.compat.rail.client;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import dimblend.blocks.compat.rail.TrainTubeLink;
import dimblend.blocks.compat.rail.TrainTubeSelection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 客户端持有的跨车厢连接表 + 自己挂起的端点选择（服务端推快照，客户端整体替换）。
 *
 * <p>维度键由载荷携带（{@code TrainTubeLinksPayload#dimension}），本类不再从 {@code Minecraft#level} 反查——
 * 收包发生在 Netty 线程、且可能在 {@code ClientLevel} 就位之前，读客户端字段既非线程安全也会把快照判成"别的维度"。
 * 现在只有"载荷维度 != 当前维度"才整份丢掉。换维度/重登会拿到新快照，登出直接清空。</p>
 */
public final class ClientTrainTubeLinks {

    private static ResourceKey<Level> dimension;
    private static List<TrainTubeLink> links = List.of();
    private static TrainTubeSelection selection;

    private ClientTrainTubeLinks() {
    }

    public static void accept(ResourceKey<Level> receivedDimension, List<TrainTubeLink> receivedLinks,
            @Nullable TrainTubeSelection receivedSelection) {
        dimension = receivedDimension;
        links = receivedLinks == null ? List.of() : List.copyOf(receivedLinks);
        selection = receivedSelection;
    }

    public static List<TrainTubeLink> links(ResourceKey<Level> currentDimension) {
        return matches(currentDimension) ? links : List.of();
    }

    @Nullable
    public static TrainTubeSelection selection(ResourceKey<Level> currentDimension) {
        return matches(currentDimension) ? selection : null;
    }

    private static boolean matches(ResourceKey<Level> currentDimension) {
        // ResourceKey 靠全局 interner 出同实例（weakValues），这里按 location 比，避开对实例同一性的依赖
        return dimension != null && currentDimension != null
                && dimension.location().equals(currentDimension.location());
    }

    public static void clear() {
        dimension = null;
        links = List.of();
        selection = null;
    }
}
