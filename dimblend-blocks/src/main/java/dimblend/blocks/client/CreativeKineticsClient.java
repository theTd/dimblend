package dimblend.blocks.client;

import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockModel;
import com.simibubi.create.content.kinetics.simpleRelays.CogWheelBlock;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogRenderer;
import com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogVisual;
import com.simibubi.create.foundation.model.ModelSwapper;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.create.CreativeKinetics;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.Map;

/**
 * K 板块客户端：创造传动件的 Flywheel visual、BER 回退、渲染层与支架模型包装。
 *
 * <p>visual：对齐 Create 自家注册（AllBlockEntityTypes 里 {@code .visual(..., false)}，
 * 即 neverSkipVanillaRender——BER 仍然注册，Flywheel 开启时 BER 自检
 * {@code VisualizationManager.supportsVisualization} 提前返回）。轴/齿轮不能用
 * Create 的 {@code BracketedKineticBlockEntityVisual::create}：其齿轮判定是
 * {@code AllBlocks.COGWHEEL.is(block)} 注册身份检查，本板块齿轮会被画成轴——
 * 自写工厂按 {@code instanceof CogWheelBlock} 分派 partial（不可复用该类本身：
 * 字节码核实它只有默认构造器 + 静态 create，无可传模型的构造途径）。</p>
 *
 * <p>识别色（2026-09-29 拍板，同日改色）：本板块旋转本体全部改用自有贴图模型
 * （{@code dimblend_blocks:block/creative_shaft|creative_cogwheel}）——<b>乌木齿轮</b>
 * （齿环 texel ×(0.58, 0.46, 0.38) 深暖褐）+<b>黑铁杆</b>（轴杆 texel
 * ×(0.50, 0.56, 0.66) 深青灰），与原版一眼区分；套壳外壳保持 Create 原色，
 * 开口内轴件仍为识别色。BER 回退路径：裸件经 {@code BracketedKineticBlockModel}
 * 包装层的 virtual 数据透传实际同样染色（复核字节码在案，良性）；encased 两件
 * BER 沿用 Create 原 partial 不染色（接受项，目标实例 Flywheel 常开）。</p>
 *
 * <p>渲染层：镜像 Create 注册链（BuilderTransformers/AllBlocks 核实）——轴、齿轮、
 * 套壳轴都是默认 solid（Create 未登记任何层）；套壳齿轮是 cutoutMipped
 * （registrate addLayer 单层语义），只登记这两个。setRenderLayer 只能在客户端
 * 加载期调用，FMLClientSetupEvent 派发时 ClientModLoader 仍在 loading。</p>
 *
 * <p>支架模型包装：Create 对轴/齿轮的烘焙模型套 {@link BracketedKineticBlockModel}
 * ——世界内几何只剩支架，旋转本体全靠 visual/BER 绘制。不包会双渲染（静态模型 +
 * visual 自转）；包了才有支架渲染。在烘焙结果上直接换，理由同 C 板块。</p>
 *
 * <p>依赖类（Create/Flywheel）只出现在方法体内与被守卫包裹的私有方法上
 * （JVM 惰性解析）。Create 未加载时守卫直接退出。</p>
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CreativeKineticsClient {

    /** 识别色模型（flywheel PartialModel 只是资源位置句柄，静态构造无加载副作用）。 */
    private static final PartialModel CREATIVE_SHAFT_PARTIAL =
            PartialModel.of(ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "block/creative_shaft"));
    private static final PartialModel CREATIVE_COGWHEEL_PARTIAL =
            PartialModel.of(ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "block/creative_cogwheel"));

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        registerVisuals();
        registerRenderLayers();
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        // BER 回退（Flywheel 关闭时）：轴/齿轮渲染整块模型自转，套壳变体画内部旋转件
        event.registerBlockEntityRenderer(CreativeKinetics.CREATIVE_BRACKETED_KINETIC.get(),
                BracketedKineticBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(CreativeKinetics.CREATIVE_ENCASED_SHAFT_BE.get(),
                ShaftRenderer::new);
        event.registerBlockEntityRenderer(CreativeKinetics.CREATIVE_ENCASED_COGWHEEL_BE.get(),
                EncasedCogRenderer::small);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        wrapBracketed(event.getModels(), CreativeKinetics.CREATIVE_SHAFT.get());
        wrapBracketed(event.getModels(), CreativeKinetics.CREATIVE_COGWHEEL.get());
    }

    private static void registerVisuals() {
        VisualizerRegistry.setVisualizer(CreativeKinetics.CREATIVE_BRACKETED_KINETIC.get(),
                SimpleBlockEntityVisualizer.builder(CreativeKinetics.CREATIVE_BRACKETED_KINETIC.get())
                        .factory(CreativeKineticsClient::createBracketedVisual)
                        .neverSkipVanillaRender()
                        .apply());
        VisualizerRegistry.setVisualizer(CreativeKinetics.CREATIVE_ENCASED_SHAFT_BE.get(),
                SimpleBlockEntityVisualizer.builder(CreativeKinetics.CREATIVE_ENCASED_SHAFT_BE.get())
                        .factory(SingleAxisRotatingVisual.of(CREATIVE_SHAFT_PARTIAL))
                        .neverSkipVanillaRender()
                        .apply());
        VisualizerRegistry.setVisualizer(CreativeKinetics.CREATIVE_ENCASED_COGWHEEL_BE.get(),
                SimpleBlockEntityVisualizer.builder(CreativeKinetics.CREATIVE_ENCASED_COGWHEEL_BE.get())
                        .factory(CreativeKineticsClient::createEncasedCogVisual)
                        .neverSkipVanillaRender()
                        .apply());
    }

    /**
     * 轴/齿轮 visual 工厂：照抄 Create {@code BracketedKineticBlockEntityVisual.create}
     * 的小件分支，改动二：齿轮判定换成 instanceof（本板块不做大齿轮，大齿轮分支省略）；
     * partial 换识别色模型。支架不在 visual 里画——与 Create 一样由烘焙模型包装承担。
     */
    private static SingleAxisRotatingVisual<BracketedKineticBlockEntity> createBracketedVisual(
            VisualizationContext context, BracketedKineticBlockEntity blockEntity, float partialTick) {
        Model model = Models.partial(blockEntity.getBlockState().getBlock() instanceof CogWheelBlock
                ? CREATIVE_COGWHEEL_PARTIAL
                : CREATIVE_SHAFT_PARTIAL);
        return new SingleAxisRotatingVisual<>(context, blockEntity, partialTick, model);
    }

    /** 套壳齿轮 visual：同 {@code EncasedCogVisual::small}，partial 换识别色模型。 */
    private static EncasedCogVisual createEncasedCogVisual(VisualizationContext context,
            com.simibubi.create.content.kinetics.base.KineticBlockEntity blockEntity, float partialTick) {
        return new EncasedCogVisual(context, blockEntity, false, partialTick,
                Models.partial(CREATIVE_COGWHEEL_PARTIAL));
    }

    private static void registerRenderLayers() {
        // 仅套壳齿轮是 cutoutMipped；轴/齿轮/套壳轴维持默认 solid（与 Create 一致，不登记）
        ItemBlockRenderTypes.setRenderLayer(CreativeKinetics.CREATIVE_ANDESITE_ENCASED_COGWHEEL.get(),
                RenderType.cutoutMipped());
        ItemBlockRenderTypes.setRenderLayer(CreativeKinetics.CREATIVE_BRASS_ENCASED_COGWHEEL.get(),
                RenderType.cutoutMipped());
    }

    /** 镜像 Create 的 {@code .onRegister(CreateRegistrate.blockModel(() -> BracketedKineticBlockModel::new))}。 */
    private static void wrapBracketed(Map<ModelResourceLocation, BakedModel> models, Block block) {
        int wrapped = 0;
        for (ModelResourceLocation location : ModelSwapper.getAllBlockStateModelLocations(block)) {
            BakedModel original = models.get(location);
            if (original == null) {
                continue;
            }
            models.put(location, new BracketedKineticBlockModel(original));
            wrapped++;
        }
        if (wrapped == 0) {
            DimBlendBlocks.LOGGER.warn("No baked model to wrap for creative kinetic {}, it will render nothing in world",
                    block);
        }
    }

    private CreativeKineticsClient() {
    }
}
