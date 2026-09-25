package dimblend.mixin;

import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorAccessor {
    @Mutable
    @Accessor("settings")
    void dimblend$setSettings(Holder<NoiseGeneratorSettings> settings);

    @Mutable
    @Accessor("globalFluidPicker")
    void dimblend$setGlobalFluidPicker(Supplier<Aquifer.FluidPicker> picker);

    @Invoker("createFluidPicker")
    static Aquifer.FluidPicker dimblend$createFluidPicker(NoiseGeneratorSettings settings) {
        throw new AssertionError();
    }
}
