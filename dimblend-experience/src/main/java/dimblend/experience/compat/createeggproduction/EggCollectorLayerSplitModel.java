package dimblend.experience.compat.createeggproduction;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * G7 产蛋器方块模型按渲染层拆分：鸡组 quad（贴图 {@code block/texture4}）归 {@code cutout}，
 * 其余（底座/玻璃罩/草）留 {@code translucent}。
 *
 * <p>原模型整块注册为 translucent，玻璃罩元素排在鸡前面；Sable 子层级渲染不按摄像机
 * 距离给半透明 quad 排序，玻璃先写深度把鸡剔掉。cutout 层在主世界、Sable 单块、
 * Sable 区块化三条路径上都先于 translucent 层绘制，鸡先写深度、玻璃后混合，
 * 不再依赖任何逐 quad 排序。鸡贴图 alpha 只有 0/255，cutout 截断与原混合观感一致。</p>
 *
 * <p>{@code renderType == null}（物品/GUI 等不分层的消费方）原样返回全部 quad。
 * 拆分结果按原模型返回列表的引用缓存（SimpleBakedModel 每个 side 返回固定列表），
 * 区块编译多线程读写，缓存用不可变快照整体替换。</p>
 */
final class EggCollectorLayerSplitModel extends BakedModelWrapper<BakedModel> {
    private static final ResourceLocation CHICKEN_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "createeggproduction", "block/texture4");
    private static final ChunkRenderTypeSet LAYERS = ChunkRenderTypeSet.of(RenderType.cutout(), RenderType.translucent());

    private record Split(List<BakedQuad> source, List<BakedQuad> chicken, List<BakedQuad> rest) {
    }

    /** 按 {@code side == null ? 6 : side.ordinal()} 索引。 */
    private final Split[] cache = new Split[Direction.values().length + 1];

    EggCollectorLayerSplitModel(BakedModel originalModel) {
        super(originalModel);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return LAYERS;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> quads = this.originalModel.getQuads(state, side, rand, data, null);
        if (renderType == null || quads.isEmpty()) {
            return quads;
        }
        Split split = split(side, quads);
        if (renderType == RenderType.cutout()) {
            return split.chicken();
        }
        if (renderType == RenderType.translucent()) {
            return split.rest();
        }
        return List.of();
    }

    private Split split(@Nullable Direction side, List<BakedQuad> quads) {
        int index = side == null ? Direction.values().length : side.ordinal();
        Split cached = this.cache[index];
        if (cached != null && cached.source() == quads) {
            return cached;
        }
        List<BakedQuad> chicken = new ArrayList<>();
        List<BakedQuad> rest = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (CHICKEN_TEXTURE.equals(quad.getSprite().contents().name())) {
                chicken.add(quad);
            } else {
                rest.add(quad);
            }
        }
        Split split = new Split(quads, List.copyOf(chicken), List.copyOf(rest));
        this.cache[index] = split;
        return split;
    }
}
