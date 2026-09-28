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
 * 包在车架方块（dimblend-blocks 换上的伪装模型）外层：脏污以 cutout 层的镂空贴层覆盖（每面一张当前档位贴图），
 * 不改底层材质。网格构建时从 BE 行为读脏污快照、从关卡读上方方块，存进模型数据。
 *
 * <p>贴层几何复制原模型的面而不是按方块形状另造：Create 伪装板的侧面由两段裁切拼成，
 * 另造的整面与之共面但顶点不同，远处会闪。代价是脏车架重建网格时多取一次原模型的面。</p>
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
        BlockState aboveState = level.getBlockState(above);
        ChassisGrimeRenderData.Above aboveKind = aboveState.isAir() ? ChassisGrimeRenderData.Above.AIR
                : aboveState.isSolidRender(level, above) ? ChassisGrimeRenderData.Above.OPAQUE
                : ChassisGrimeRenderData.Above.OTHER;
        return data.derive().with(GRIME, new ChassisGrimeRenderData(visual, aboveKind)).build();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet base = super.getRenderTypes(state, rand, data);
        // 伪装方块本就登记四层全开，通常已含 cutout，免去每次重建的并集分配
        if (data.get(GRIME) == null || base.contains(RenderType.cutout())) {
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
            ChassisGrimeQuads.addGravel(quads, grime.visual(), grime.above(), sprites);
        }
        return quads;
    }
}
