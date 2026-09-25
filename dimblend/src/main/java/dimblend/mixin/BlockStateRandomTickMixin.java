package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.time.ServerBandTime;
import dimblend.weather.ServerBandWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateRandomTickMixin {
    @WrapMethod(method = "randomTick")
    private void dimblend$bandTimeRandomTick(ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        ServerBandTime.run(level, pos, () -> ServerBandWeather.run(level, pos, () -> original.call(level, pos, random)));
    }

    @WrapMethod(method = "tick")
    private void dimblend$bandTimeTick(ServerLevel level, BlockPos pos, RandomSource random, Operation<Void> original) {
        ServerBandTime.run(level, pos, () -> ServerBandWeather.run(level, pos, () -> original.call(level, pos, random)));
    }
}
