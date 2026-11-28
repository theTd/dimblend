package dimblend.mixin.sodium;

import dimblend.client.bandrift.SodiumRiftBridge;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records which root shader {@link ShaderLoader#loadShader} is parsing, so
 * {@link ShaderParserInjectionMixin} can rewrite exactly {@code sodium:blocks/block_layer_opaque}
 * and nothing else.
 *
 * <p>{@code #import} expansion never re-enters {@code loadShader} (it goes through
 * {@code ShaderLoader.getShaderSource}), so a single slot is enough — no stack. The slot is
 * cleared on RETURN; a shader that throws during parsing leaves a stale value that can only ever
 * match the same file on the next load, and every load starts by overwriting it.</p>
 */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderProbeMixin {

    @Inject(method = "loadShader", at = @At("HEAD"))
    private static void dimblend$beginShader(
            ShaderType type, ResourceLocation name, ShaderConstants constants,
            CallbackInfoReturnable<GlShader> cir) {
        SodiumRiftBridge.beginShader(name);
    }

    @Inject(method = "loadShader", at = @At("RETURN"))
    private static void dimblend$endShader(
            ShaderType type, ResourceLocation name, ShaderConstants constants,
            CallbackInfoReturnable<GlShader> cir) {
        SodiumRiftBridge.endShader();
    }
}
