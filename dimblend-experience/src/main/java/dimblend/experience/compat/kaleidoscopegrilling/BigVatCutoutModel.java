package dimblend.experience.compat.kaleidoscopegrilling;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 森罗大缸整块改画在 {@code cutout} 层，修它在 Sable 子层级上的透视/穿帮。
 *
 * <p><b>与产蛋器（{@code EggCollectorLayerSplitModel}）同一个病根。</b>大缸在
 * {@code ClientSetup} 里用 {@code ItemBlockRenderTypes.setRenderLayer(...,
 * RenderType.translucent())} 整块注册成半透明层。Sable 渲染子层级时**不按摄像机距离
 * 给半透明 quad 排序**，于是大缸外层（y12-16 的缸沿）先写深度，把内壁和开口后面本该
 * 可见的部分剔掉/漏出背景——实机表现就是从缸口看进去能看见后面的地形。</p>
 *
 * <p><b>为什么可以直接改成 cutout 而不是像产蛋器那样拆分两层。</b>大缸的方块贴图
 * {@code kaleidoscope_grilling:block/big_vat} 是 64×64，实测 alpha 只有两种取值
 * （2308 个 255、1788 个 0、**半透明像素 0 个**），所以 cutout 的 0/255 截断与原
 * translucent 混合结果完全一致，主世界观感不变。缸里那层油面由 {@code BigVatRenderer}
 * 另画，不在这份模型里，因此不存在「必须保留 translucent 的部件」。</p>
 *
 * <p>{@code renderType == null} 的消费方（物品/GUI 等）不受影响：只覆盖
 * {@link #getRenderTypes(BlockState, RandomSource, ModelData)}，quad 一律原样透传。</p>
 */
final class BigVatCutoutModel extends BakedModelWrapper<BakedModel> {
    private static final ChunkRenderTypeSet CUTOUT = ChunkRenderTypeSet.of(RenderType.cutout());

    BigVatCutoutModel(BakedModel originalModel) {
        super(originalModel);
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        return CUTOUT;
    }

    /** quad 不变，只是换个层画；显式覆写让「只改层」这件事在代码里看得见。 */
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData data, @Nullable RenderType renderType) {
        return this.originalModel.getQuads(state, side, rand, data, renderType);
    }
}