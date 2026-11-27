package dimblend.blocks.compat.rail.client;

import java.util.Map;

import com.simibubi.create.content.fluids.PipeAttachmentModel;
import com.simibubi.create.foundation.model.ModelSwapper;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.rail.TrainTubes;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 列车加压管道的客户端接入点：支架渲染、跨车厢线缆渲染、"登出即清连接表"。
 *
 * <p>只在 {@code create && sable && dist.isClient()} 下由 {@link dimblend.blocks.DimBlendBlocks}
 * 调用；本类硬引用 {@code ClientSubLevel} 等客户端类型。</p>
 */
public final class TrainTubeClient {

    private TrainTubeClient() {
    }

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(TrainTubeCableRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(TrainTubeClient::onLoggingOut);
        modEventBus.addListener(TrainTubeClient::onModifyBakingResult);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientTrainTubeLinks.clear();
    }

    /**
     * 把管道的每个 blockstate 模型包一层 Create 的 {@link PipeAttachmentModel}：它从
     * {@code BracketedBlockEntityBehaviour} 读支架状态并渲染支架，本方块自己的 multipart
     * 模型体照常由被包装模型提供。
     *
     * <p><b>不能</b>走 {@code CreateClient.MODEL_SWAPPER.getCustomBlockModels().register(...)}：
     * {@code CustomBlockModels} 在第一次 {@code forEach} 时把登记表固化成
     * {@code IdentityHashMap}，而这次 {@code forEach} 就发生在烘焙事件里，晚于
     * {@code FMLClientSetupEvent}，晚注册会被静默丢掉（参见
     * {@code dimblend.blocks.client.CreativeCopycatClient#onModifyBakingResult} 的同类修复）。</p>
     */
    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int wrapped = 0;
        for (ModelResourceLocation location : ModelSwapper.getAllBlockStateModelLocations(TrainTubes.TUBE.get())) {
            BakedModel original = models.get(location);
            if (original == null) {
                continue;
            }
            models.put(location, PipeAttachmentModel.withAO(original));
            wrapped++;
        }
        if (wrapped == 0) {
            DimBlendBlocks.LOGGER.warn("No baked model to wrap for train pressured tube, its bracket will not render");
        }
    }
}
