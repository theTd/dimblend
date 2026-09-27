package dimblend.carwash.client;

import dimblend.carwash.DimBlendCarwash;
import dimblend.carwash.chassis.ChassisBlocks;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;

import java.util.Map;

/**
 * 客户端装配（MOD 总线）：注册镂空贴图精灵源；烘焙结果上给车架方块的每个状态模型套 {@link ChassisGrimeModel}。
 * mods.toml 声明 dimblend_blocks 为 AFTER，保证此时伪装模型已被 dimblend-blocks 换上。
 */
@EventBusSubscriber(modid = DimBlendCarwash.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class CarwashClientSetup {

    @SubscribeEvent
    public static void onRegisterSpriteSourceTypes(RegisterSpriteSourceTypesEvent event) {
        event.register(DimBlendCarwash.id("grime"), GrimeSpriteSource.TYPE);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ChassisGrimeSprites.resolve(event.getTextureGetter());
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int wrapped = 0;
        for (Block block : ChassisBlocks.candidateBlocks()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel original = models.get(location);
                if (original != null && !(original instanceof ChassisGrimeModel)) {
                    models.put(location, new ChassisGrimeModel(original));
                    wrapped++;
                }
            }
        }
        if (wrapped == 0) {
            DimBlendCarwash.LOGGER.warn("No chassis block models found to wrap; chassis grime will not render");
        }
    }

    private CarwashClientSetup() {
    }
}
