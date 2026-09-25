package dimblend.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Exact vertical translation of an arbitrary density field: every sample of the argument is
 * taken at y - offset, so the terrain the argument produces appears offset blocks higher with
 * no other change (F'(x,y,z) = F(x,y - offset,z)). Unlike adding a constant density bias —
 * which distorts flat-topped or clamp-saturated terrain — shifting the sample Y moves the
 * whole field uniformly, hill tops included.
 *
 * <p>Used to move another mod's noise terrain (Twilight Forest +64, Voidscape -64) by
 * referencing that mod's own NoiseGeneratorSettings instead of forking its density
 * functions and surface rules into data files that must be manually kept in sync. The
 * matching biome-column shift lives in {@link YShiftedBiomeSource}; Twilight structure
 * Y unclamp in {@link YShiftedStructureElevation}. Both run on the y-shifted delegate
 * itself.
 *
 * <p>The shifted context is a plain delegate; {@link ShiftedContext} keeps a mutable delegate
 * slot so {@link #fillArray} can reuse one wrapper per batch instead of allocating per cell.
 */
public record YShiftedDensity(DensityFunction argument, int offset) implements DensityFunction {
    public static final MapCodec<YShiftedDensity> DIRECT_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument").forGetter(YShiftedDensity::argument),
            Codec.INT.fieldOf("offset").forGetter(YShiftedDensity::offset)
    ).apply(instance, YShiftedDensity::new));
    public static final KeyDispatchDataCodec<YShiftedDensity> CODEC = KeyDispatchDataCodec.of(DIRECT_CODEC);

    @Override
    public double compute(FunctionContext context) {
        return this.argument.compute(new ShiftedContext(context, this.offset));
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        ShiftedContext shifted = new ShiftedContext(null, this.offset);
        for (int i = 0; i < array.length; i++) {
            shifted.delegate = contextProvider.forIndex(i);
            array[i] = this.argument.compute(shifted);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new YShiftedDensity(this.argument.mapAll(visitor), this.offset));
    }

    @Override
    public double minValue() {
        return this.argument.minValue();
    }

    @Override
    public double maxValue() {
        return this.argument.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /**
     * FunctionContext reporting blockY() shifted down. The delegate slot is mutable so a
     * single instance serves a whole fillArray batch; compute calls are synchronous and never
     * retain the context, so serial reuse is safe.
     */
    private static final class ShiftedContext implements FunctionContext {
        private FunctionContext delegate;
        private final int offset;

        private ShiftedContext(FunctionContext delegate, int offset) {
            this.delegate = delegate;
            this.offset = offset;
        }

        @Override
        public int blockX() {
            return this.delegate.blockX();
        }

        @Override
        public int blockY() {
            return this.delegate.blockY() - this.offset;
        }

        @Override
        public int blockZ() {
            return this.delegate.blockZ();
        }

        @Override
        public Blender getBlender() {
            return this.delegate.getBlender();
        }
    }
}
