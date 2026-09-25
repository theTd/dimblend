package dimblend.blocks.client;

import com.copycatsplus.copycats.content.copycat.beam.CopycatBeamModelCore;
import com.copycatsplus.copycats.content.copycat.slab.CopycatMultiSlabModelCore;
import com.copycatsplus.copycats.foundation.copycat.ICopycatBlock;
import com.copycatsplus.copycats.foundation.copycat.model.CopycatModelCore;
import com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlock;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.content.decoration.copycat.CopycatPanelModel;
import com.simibubi.create.foundation.model.ModelSwapper;
import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.copycats.CreativeCopycats;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.Map;
import java.util.function.Function;

/**
 * C 板块客户端：创造模式伪装方块的模型、渲染层和染色。
 *
 * <p>方块状态文件指向 {@code minecraft:block/air}，世界内几何完全来自烘焙期换上的
 * 伪装模型。渲染层必须四层全开（与 Copycats {@code multiCopycat}/Create {@code copycat}
 * 相同）：未登记时方块渲染层默认是 solid，而伪装基底和草、玻璃等材质不在 solid。
 * {@code CopycatModel.getQuads} 在 renderType 不匹配时回退空气模型的空面。
 * 邻居面剔除靠 {@code noOcclusion}，不在这里处理。</p>
 *
 * <p>依赖类只出现在方法体内（JVM 惰性解析）。Copycats+ 未加载时守卫直接退出。</p>
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CreativeCopycatClient {

    private static final ChunkRenderTypeSet ALL_LAYERS = ChunkRenderTypeSet.of(
            RenderType.solid(),
            RenderType.cutout(),
            RenderType.cutoutMipped(),
            RenderType.translucent());

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("copycats")) {
            return;
        }
        // setRenderLayer 只能在客户端加载期调用。FMLClientSetupEvent 派发时 ClientModLoader 仍在 loading。
        registerRenderLayers(CreativeCopycats.CREATIVE_COPYCAT_SLAB.get());
        registerRenderLayers(CreativeCopycats.CREATIVE_COPYCAT_BEAM.get());
        registerRenderLayers(CreativeCopycats.CREATIVE_COPYCAT_PANEL.get());
    }

    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        if (!ModList.get().isLoaded("copycats")) {
            return;
        }
        event.register(IMultiStateCopycatBlock.wrappedColor(), CreativeCopycats.CREATIVE_COPYCAT_SLAB.get());
        event.register(ICopycatBlock.wrappedColor(), CreativeCopycats.CREATIVE_COPYCAT_BEAM.get());
        event.register(CopycatBlock.wrappedColor(), CreativeCopycats.CREATIVE_COPYCAT_PANEL.get());
    }

    /**
     * 在烘焙结果上直接换模型，而不是事先塞进 Create 的 {@code CustomBlockModels}。
     * 后者第一次 {@code forEach} 就冻结登记表，客户端 setup 若晚于首次烘焙，
     * 之后的 register 会被静默丢掉，方块永远停在空气模型。
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ModList.get().isLoaded("copycats")) {
            return;
        }
        var models = event.getModels();
        swap(models, CreativeCopycats.CREATIVE_COPYCAT_SLAB.get(),
                model -> CopycatModelCore.createModel(model, new CopycatMultiSlabModelCore()));
        swap(models, CreativeCopycats.CREATIVE_COPYCAT_BEAM.get(),
                model -> CopycatModelCore.createModel(model, new CopycatBeamModelCore()));
        swap(models, CreativeCopycats.CREATIVE_COPYCAT_PANEL.get(), CreativeCopycatPanelModel::new);
    }

    private static void registerRenderLayers(Block block) {
        ItemBlockRenderTypes.setRenderLayer(block, ALL_LAYERS);
    }

    private static void swap(Map<ModelResourceLocation, BakedModel> models, Block block,
            Function<BakedModel, BakedModel> factory) {
        int swapped = 0;
        for (ModelResourceLocation location : ModelSwapper.getAllBlockStateModelLocations(block)) {
            BakedModel original = models.get(location);
            if (original == null) {
                continue;
            }
            models.put(location, factory.apply(original));
            swapped++;
        }
        if (swapped == 0) {
            DimBlendBlocks.LOGGER.warn("No baked model to wrap for creative copycat {}, it will render as air",
                    block);
        }
    }

    /**
     * Create 的 {@link CopycatPanelModel} 不覆盖渲染层，会落到被包装的空气模型上，
     * 再查方块渲染层。这里固定返回四层，避免只在 solid 上取样时把 cutout/translucent 材质画成空面。
     * 与 {@link #registerRenderLayers} 是重复保险：空气模型本来就会回落到方块已登记的渲染层。
     */
    private static final class CreativeCopycatPanelModel extends CopycatPanelModel {
        private CreativeCopycatPanelModel(BakedModel originalModel) {
            super(originalModel);
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
            return ALL_LAYERS;
        }
    }

    private CreativeCopycatClient() {
    }
}
