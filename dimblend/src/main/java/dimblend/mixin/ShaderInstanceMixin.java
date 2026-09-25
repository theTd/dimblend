package dimblend.mixin;

import com.mojang.blaze3d.shaders.Uniform;
import java.util.List;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla {@link ShaderInstance#apply()} re-queries every sampler location with
 * {@code glGetUniformLocation} each frame, even though {@code updateLocations()}
 * already cached them in {@code samplerLocations}. That driver call showed up as
 * ~13s of Render-thread samples in a 60s spark profile (via GUI text
 * {@code drawString → endBatch → apply}). Use the cached locations instead.
 *
 * {@code samplerNames} and {@code samplerLocations} stay parallel after
 * {@code updateLocations()} removes missing samplers; texture-unit index {@code j}
 * is unchanged.
 *
 * {@code require = 0}: Sodium 0.8.x applies the same redirect in its own
 * ShaderInstanceMixin. When Sodium is present it wins (equal priority, applied
 * first) and this one silently yields instead of failing injection.
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin {
    @Shadow
    @Final
    private List<Integer> samplerLocations;

    @Shadow
    @Final
    private List<String> samplerNames;

    @Redirect(
        method = "apply",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/shaders/Uniform;glGetUniformLocation(ILjava/lang/CharSequence;)I"
        ),
        require = 0
    )
    private int dimblend$useCachedSamplerLocation(int programId, CharSequence name) {
        int idx = this.samplerNames.indexOf(name);
        if (idx >= 0 && idx < this.samplerLocations.size()) {
            return this.samplerLocations.get(idx);
        }
        return Uniform.glGetUniformLocation(programId, name);
    }
}
