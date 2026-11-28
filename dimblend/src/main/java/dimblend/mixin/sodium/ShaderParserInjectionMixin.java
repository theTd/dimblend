package dimblend.mixin.sodium;

import dimblend.client.bandrift.SodiumRiftBridge;
import dimblend.client.bandrift.SodiumRiftShaderSource;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects the volumetric rift mask into Sodium's chunk shader <b>source</b>, at the single point
 * where it is still a string: {@code ShaderParser.parseShader(String, ShaderConstants)}.
 *
 * <p>Sodium reads its shaders from the class loader, not from resource packs
 * ({@code ShaderLoader.getShaderSource}), so a resource-pack override cannot work and this method
 * is the only stable hook. Veil also wraps it ({@code @ModifyReturnValue}, default priority); the
 * config-level priority 1500 makes us run after it, and the injected text is plain GLSL 330 so
 * Veil's AST rewrite stays valid even if the order flips.</p>
 *
 * <p>Injection is keyed by the root shader recorded in {@link ShaderLoaderProbeMixin} and is
 * idempotent. The actual string surgery (and its fallback anchors) lives in
 * {@link SodiumRiftShaderSource} so it can be unit tested against the real Sodium sources; this
 * class only decides which stage is being parsed and reports the outcome.</p>
 */
@Mixin(value = ShaderParser.class, remap = false)
public abstract class ShaderParserInjectionMixin {

    @Inject(
            method = "parseShader(Ljava/lang/String;Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;)"
                    + "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderParser$ParsedShader;",
            at = @At("RETURN"),
            cancellable = true)
    private static void dimblend$injectRiftMask(
            String source, ShaderConstants constants,
            CallbackInfoReturnable<ShaderParser.ParsedShader> cir) {
        ShaderParser.ParsedShader original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        ResourceLocation current = SodiumRiftBridge.currentShader();
        if (current == null) {
            return;
        }
        String path = current.getPath();
        boolean vertex = path.endsWith("blocks/block_layer_opaque.vsh");
        boolean fragment = path.endsWith("blocks/block_layer_opaque.fsh");
        if (!vertex && !fragment) {
            return;
        }
        String src = original.src();
        if (SodiumRiftShaderSource.injected(src)) {
            SodiumRiftBridge.notePresent(vertex);
            return;
        }
        SodiumRiftBridge.noteAnchor(vertex,
                vertex ? SodiumRiftShaderSource.hasVertexPositionAnchor(src)
                        : SodiumRiftShaderSource.hasFragmentFogAnchor(src));
        String injected = SodiumRiftShaderSource.inject(vertex, src);
        if (injected == null) {
            SodiumRiftBridge.noteFailure(
                    "rift shader anchor missing in " + path + " — S2 stays off, membrane fallback");
            return;
        }
        cir.setReturnValue(new ShaderParser.ParsedShader(injected, original.includeIds()));
        SodiumRiftBridge.noteInjection(vertex, injected);
    }
}
