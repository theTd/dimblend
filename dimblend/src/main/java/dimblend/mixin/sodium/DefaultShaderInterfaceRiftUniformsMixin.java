package dimblend.mixin.sodium;

import com.mojang.blaze3d.platform.GlStateManager;
import dimblend.client.bandrift.SodiumRiftBridge;
import dimblend.client.bandrift.VolumetricRiftState;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the six rift uniforms into Sodium's chunk shader and swaps the lightmap during the far
 * pass.
 *
 * <p>Sodium's {@code ChunkShaderInterface} has no third-party uniform slots, so the program is
 * extended the same way Veil extends it: bind the (optional) uniforms once at construction and
 * write them at {@code setRegionOffset}, which Sodium already calls once per region per pass.
 * Values come from {@link VolumetricRiftState}, the same holder the mirage and far-sky shaders
 * read, so all three passes agree on the shell.</p>
 *
 * <p><b>Lightmap.</b> The far band must not be lit by the near band's lightmap. Writing
 * {@code RenderSystem.setShaderTexture(2, ...)} before the pass does not survive Sodium: every
 * pass begins with {@code RenderType.setupRenderState()}, whose LIGHTMAP shard rebinds the main
 * lightmap. The only point after that rebind is {@code setupState} TAIL, where we rebind unit 1
 * (Sodium's LIGHT slot) to the band lightmap for the duration of the far pass.</p>
 */
@Mixin(value = DefaultShaderInterface.class, remap = false)
public abstract class DefaultShaderInterfaceRiftUniformsMixin {
    @Unique
    private GlUniformInt dimblend$uRiftMode;
    @Unique
    private GlUniformFloat4v dimblend$uRiftPlane;
    @Unique
    private GlUniformFloat4v dimblend$uRiftCenter;
    @Unique
    private GlUniformFloat4v dimblend$uRiftRadii;
    @Unique
    private GlUniformFloat4v dimblend$uRiftStyle;
    @Unique
    private GlUniformFloat4v dimblend$uRiftCam;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void dimblend$bindRiftUniforms(
            ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        this.dimblend$uRiftMode = context.bindUniformOptional("u_RiftMode", GlUniformInt::new);
        this.dimblend$uRiftPlane = context.bindUniformOptional("u_RiftPlane", GlUniformFloat4v::new);
        this.dimblend$uRiftCenter = context.bindUniformOptional("u_RiftCenter", GlUniformFloat4v::new);
        this.dimblend$uRiftRadii = context.bindUniformOptional("u_RiftRadii", GlUniformFloat4v::new);
        this.dimblend$uRiftStyle = context.bindUniformOptional("u_RiftStyle", GlUniformFloat4v::new);
        this.dimblend$uRiftCam = context.bindUniformOptional("u_RiftCam", GlUniformFloat4v::new);
    }

    @Inject(method = "setRegionOffset", at = @At("TAIL"))
    private void dimblend$writeRiftUniforms(float x, float y, float z, CallbackInfo ci) {
        if (this.dimblend$uRiftMode == null) {
            return; // injection missed: the mask uniforms do not exist in this program
        }
        this.dimblend$uRiftMode.setInt(VolumetricRiftState.mode());
        if (this.dimblend$uRiftPlane != null) {
            this.dimblend$uRiftPlane.set(VolumetricRiftState.plane());
        }
        if (this.dimblend$uRiftCenter != null) {
            this.dimblend$uRiftCenter.set(VolumetricRiftState.center());
        }
        if (this.dimblend$uRiftRadii != null) {
            this.dimblend$uRiftRadii.set(VolumetricRiftState.radii());
        }
        if (this.dimblend$uRiftStyle != null) {
            this.dimblend$uRiftStyle.set(VolumetricRiftState.style());
        }
        if (this.dimblend$uRiftCam != null) {
            this.dimblend$uRiftCam.set(VolumetricRiftState.cam());
        }
    }

    @Inject(method = "setupState", at = @At("TAIL"))
    private void dimblend$bandLightmap(CallbackInfo ci) {
        int textureId = VolumetricRiftState.lightTextureOverride();
        if (textureId > 0) {
            // Sodium's LIGHT slot is GL texture unit 1 (BLOCK is 0). setupState already bound the
            // main lightmap here, so this is the last word for the current pass.
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            GlStateManager._bindTexture(textureId);
            return;
        }
        // No override: this is a normal pass, so the texture Sodium just bound into the LIGHT slot
        // is the main lightmap. Remember its id once — the mirage pass needs it after the terrain
        // render type has already cleared the shader texture slot back to 0.
        SodiumRiftBridge.noteMainLightmap(
                com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(2));
    }
}
