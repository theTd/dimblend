package dimblend.blocks.client;

import dimblend.blocks.api.CreativeCopycatHiding;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 隐藏感知包装：套在伪装模型最外层（材质/CT 装饰仍在内层原样工作）。
 * 隐藏时整块不产出任何面——不走方块状态，Sable 子关卡不触发物理更新；
 * 状态来源是 BE 的 {@link dimblend.blocks.api.CreativeCopycatHidable} 标记，
 * 在烘焙数据收集时现场读取（与 Copycats+ 读材质同一时机，网格线程安全）。
 */
public class HidingCopycatModel extends BakedModelWrapper<BakedModel> {

    public static final ModelProperty<Boolean> HIDDEN = new ModelProperty<>();

    public HidingCopycatModel(BakedModel originalModel) {
        super(originalModel);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state,
            ModelData blockEntityData) {
        // 隐藏时其余数据全部无关（反正不产面），只打 HIDDEN 标记
        if (CreativeCopycatHiding.isHidden(level, pos)) {
            return ModelData.builder().with(HIDDEN, Boolean.TRUE).build();
        }
        return super.getModelData(level, pos, state, blockEntityData);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData data, @Nullable RenderType renderType) {
        if (Boolean.TRUE.equals(data.get(HIDDEN))) {
            return List.of();
        }
        return super.getQuads(state, side, rand, data, renderType);
    }
}
