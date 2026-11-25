package dimblend.blocks.compat.rail.client;

import java.util.List;

import dimblend.blocks.compat.rail.TrainTubeLink;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 客户端持有的跨车厢连接表（服务端推快照，客户端整体替换）。
 *
 * <p>只在"当前维度的快照"有效：换维度/重登会拿到新快照，登出直接清空，
 * 避免绘图时引用别的维度的坐标。</p>
 */
public final class ClientTrainTubeLinks {

    private static ResourceKey<Level> dimension;
    private static List<TrainTubeLink> links = List.of();

    private ClientTrainTubeLinks() {
    }

    public static void accept(List<TrainTubeLink> received) {
        Minecraft minecraft = Minecraft.getInstance();
        dimension = minecraft.level == null ? null : minecraft.level.dimension();
        links = received == null ? List.of() : received;
    }

    public static List<TrainTubeLink> get(ResourceKey<Level> currentDimension) {
        if (dimension == null || !dimension.equals(currentDimension)) {
            return List.of();
        }
        return links;
    }

    public static void clear() {
        dimension = null;
        links = List.of();
    }
}
