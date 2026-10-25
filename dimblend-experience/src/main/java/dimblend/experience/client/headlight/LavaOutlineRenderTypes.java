package dimblend.experience.client.headlight;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

/**
 * The see-through half of the lit-lava outline: {@link RenderType#lines()} without the depth test and
 * without the depth write, drawn into the main render target at the dimmed alpha before the ordinary
 * depth-tested pass paints the camera-visible parts at the full pulse alpha.
 *
 * <p><b>The render type.</b> Same shader ({@code RENDERTYPE_LINES_SHADER}), line width
 * ({@code LineStateShard(OptionalDouble.empty())}, i.e. window-scaled), transparency
 * ({@code TRANSLUCENT_TRANSPARENCY}), layering ({@code VIEW_OFFSET_Z_LAYERING}), culling ({@code NO_CULL})
 * and vertex format ({@code POSITION_COLOR_NORMAL}, {@code LINES}) as {@link RenderType#lines()}; only the
 * depth test ({@code NO_DEPTH_TEST}), the write mask ({@code COLOR_WRITE}: no depth write) and the output
 * target differ. NeoForge makes {@code RenderType.create} and the {@code RenderStateShard} constants public
 * through its access transformer, so this holder needs no extending shim.
 *
 * <p><b>Depth test, not just its name.</b> Vanilla's {@link RenderStateShard#NO_DEPTH_TEST} is a no-op for
 * {@code GL_ALWAYS}: its setup only enables the test for the other functions and relies on a preceding
 * depth-tested type's clear having disabled it. At {@code AFTER_TRANSLUCENT_BLOCKS} the translucent chunk
 * layer is still set up, with LEQUAL enabled, so {@link #SEE_THROUGH_DEPTH} really disables the test (and
 * restores the previous enabled state in its clear). Without that, the "see-through" pass would still be
 * depth-tested and walls would keep hiding the dim line.
 *
 * <p><b>Why not {@code MAIN_TARGET} verbatim.</b> Vanilla's {@link RenderStateShard#MAIN_TARGET} has no-op
 * setup and clear: it assumes the caller already bound main. {@code AFTER_TRANSLUCENT_BLOCKS} is fired from
 * inside {@code LevelRenderer.renderSectionLayer(RenderType.translucent())}, before that layer clears its
 * state, so under Fabulous the bound target at this point is the translucent target, not main. A
 * {@code MAIN_TARGET} line would therefore land in the translucent target, and the transparency post chain
 * composites that with {@code dst * (1 - a) + src.rgb} ({@code transparency.fsh}) - the colour is not
 * multiplied by alpha, so 40% would not read as 40%. {@link #SEE_THROUGH_TARGET} binds main for this pass
 * and puts the translucent target back in its clear, which is the state the surrounding layer expects.
 *
 * <p><b>Fabulous.</b> At this stage main holds the opaque terrain and the regular entities. The transparency
 * chain ({@code shaders/post/transparency.json}) later uses main colour and depth as its base layer and
 * blends the translucent, item-entity, particles, cloud and weather targets over it depth-sorted. A colour
 * already in main is part of that base layer, so no later opaque pass can cover it: a wall, the terrain or
 * the train cab cannot hide the dim line. (Translucent, particle or cloud layers nearer than the main depth
 * still tint it, as they tint everything behind them.) The full-alpha pass keeps writing to
 * {@code ITEM_ENTITY_TARGET}; the chain composites it at the segment's own, nearer depth, on top of the dim
 * base wherever the depth test passed.
 *
 * <p>Writing the dim pass to {@code ITEM_ENTITY_TARGET} instead would be wrong for the same reason it is
 * wrong in the translucent target: without a depth write it inherits the item target's copied pre-entity
 * main depth (at a cab pixel, the terrain behind the cab), so the chain sorts it behind that nearer entity,
 * and its blend does not scale the colour by alpha. Neither the wall case nor the 40% would be reliable.
 *
 * <p><b>Fancy and Fast.</b> There is no transparency chain and main is already bound, so the extra bind is
 * harmless and the line blends straight into the image after all opaque and translucent geometry. The
 * see-through pass ignores the depth buffer in every mode, so none of them hides it behind a wall.
 */
final class LavaOutlineRenderTypes {
    /**
     * Effective {@link RenderStateShard#NO_DEPTH_TEST} for this pass. Vanilla's shard does nothing for
     * {@code GL_ALWAYS} (it expects the test to be off already), but the translucent chunk layer has LEQUAL
     * enabled while this stage runs, so switch it off here and put the previous enabled state back.
     */
    private static final RenderStateShard.DepthTestStateShard SEE_THROUGH_DEPTH =
            new RenderStateShard.DepthTestStateShard("always", 519) {
                private boolean wasEnabled;

                @Override
                public void setupRenderState() {
                    wasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                    RenderSystem.disableDepthTest();
                }

                @Override
                public void clearRenderState() {
                    if (wasEnabled) {
                        RenderSystem.enableDepthTest();
                    }
                }
            };

    /**
     * Output state of the see-through pass. Binds main although the surrounding translucent chunk layer has
     * the translucent target bound under Fabulous; the clear restores that target. Only correct at
     * {@code AFTER_TRANSLUCENT_BLOCKS}, where the surrounding layer is {@link RenderType#translucent()}.
     */
    private static final RenderStateShard.OutputStateShard SEE_THROUGH_TARGET =
            new RenderStateShard.OutputStateShard("dimblend_lava_outline_see_through",
                    () -> Minecraft.getInstance().getMainRenderTarget().bindWrite(false),
                    LavaOutlineRenderTypes::restoreStageTarget);

    /**
     * Puts back the target the stage had before the outline: the translucent target under Fabulous, nothing
     * to do under Fancy/Fast where main is the target. The full-alpha line's own clear always rebinds main,
     * so the caller also calls this after that batch.
     */
    static void restoreStageTarget() {
        if (Minecraft.useShaderTransparency()) {
            Minecraft.getInstance().levelRenderer.getTranslucentTarget().bindWrite(false);
        }
    }

    /** Vanilla lines' state minus depth test/write, plus main-target output: the dimmed under-pass. */
    static final RenderType SEE_THROUGH_LINES = RenderType.create(
            "dimblend_lava_outline_see_through",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(SEE_THROUGH_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setDepthTestState(SEE_THROUGH_DEPTH)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    private LavaOutlineRenderTypes() {
    }
}
