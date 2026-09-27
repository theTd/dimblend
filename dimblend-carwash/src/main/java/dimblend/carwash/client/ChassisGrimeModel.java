package dimblend.carwash.client;

import dimblend.carwash.chassis.ChassisBlocks;
import dimblend.carwash.chassis.ChassisGrimeBehaviour;
import dimblend.carwash.chassis.ChassisGrimeVisual;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 包在车架方块（dimblend-blocks 换上的伪装模型）外层：脏污以 cutout 层的镂空贴层叠加，
 * 不改底层材质。网格构建时从 BE 行为读脏污快照、从关卡读上方方块，存进模型数据。
 */
public class ChassisGrimeModel extends BakedModelWrapper<BakedModel> {

    public static final ModelProperty<ChassisGrimeRenderData> GRIME = new ModelProperty<>();

    private static final ChunkRenderTypeSet OVERLAY_LAYER = ChunkRenderTypeSet.of(RenderType.cutout());

    public ChassisGrimeModel(BakedModel originalModel) {
        super(originalModel);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        ModelData data = super.getModelData(level, pos, state, modelData);
        if (!ChassisBlocks.isChassis(state)) {
            return data;
        }
        ChassisGrimeVisual visual = ChassisGrimeBehaviour.visualAt(level, pos);
        if (visual.isClean()) {
            return data;
        }
        BlockPos above = pos.above();
        boolean aboveSolid = level.getBlockState(above).isSolidRender(level, above);
        return data.derive().with(GRIME, new ChassisGrimeRenderData(visual, aboveSolid)).build();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet base = super.getRenderTypes(state, rand, data);
        if (data.get(GRIME) == null) {
            return base;
        }
        return ChunkRenderTypeSet.union(base, OVERLAY_LAYER);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
            ModelData extraData, @Nullable RenderType renderType) {
        List<BakedQuad> base = super.getQuads(state, side, rand, extraData, renderType);
        if (renderType != RenderType.cutout()) {
            return base;
        }
        ChassisGrimeRenderData grime = extraData.get(GRIME);
        ChassisGrimeSprites.Resolved sprites = ChassisGrimeSprites.get();
        if (grime == null || sprites == null) {
            return base;
        }
        List<BakedQuad> quads = new ArrayList<>(base);
        // 贴层几何取原模型全部渲染层的面（renderType = null 即全部，Create/Copycats+ 伪装模型均如此约定），
        // 按同一个 side 取，剔除行为与底层一致
        ChassisGrimeQuads.addDirt(quads, originalModel.getQuads(state, side, rand, extraData, null), grime.visual(), sprites);
        if (side == null) {
            ChassisGrimeQuads.addGravel(quads, grime.visual(), grime.aboveSolid(), sprites);
        }
        return quads;
    }
}
