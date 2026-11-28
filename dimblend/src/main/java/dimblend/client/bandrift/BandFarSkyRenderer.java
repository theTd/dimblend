package dimblend.client.bandrift;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

/**
 * The far band's sky: one full-screen quad drawn at the far plane, kept only where the rift shell
 * claims the pixel for the other universe.
 *
 * <p>{@code depthMask} is off and the quad sits at {@code z = 0.999999} with {@code GL_LEQUAL}: it
 * can never occlude anything already in the depth buffer, so the near band's sky and terrain stay
 * untouched; every pixel where the shell says "other universe" is painted with the far lane's
 * gradient, stars/embers and the rift's outline glow. The far terrain pass then draws over it
 * normally.</p>
 *
 * <p>The view ray is reconstructed from {@code inverse(projection * modelView)}; {@code modelView}
 * here is rotation-only (the camera translation is folded into the camera-relative vertex data
 * Sodium uses), so unprojecting NDC {@code (x, y, 1, 1)} yields a camera-relative world direction.</p>
 */
public final class BandFarSkyRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Matrix4f INVERSE = new Matrix4f();

    private static VertexBuffer quad;
    private static ShaderInstance cached;
    private static Uniform invViewProj;
    private static Uniform zenith;
    private static Uniform horizon;
    private static Uniform stars;
    private static Uniform riftMode;
    private static Uniform riftPlane;
    private static Uniform riftCenter;
    private static Uniform riftRadii;
    private static Uniform riftStyle;
    private static Uniform riftCam;

    private BandFarSkyRenderer() {
    }

    /**
     * Draws the far sky for {@code farLaneName}. Caller has already applied the far fog and set
     * the mode to 2.
     *
     * @return true when a draw was issued
     */
    public static boolean render(RenderLevelStageEvent event, @Nullable String farLaneName) {
        ShaderInstance shader = BandRiftShaders.farSky();
        VertexBuffer buffer = quad();
        if (shader == null || buffer == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        bindUniforms(shader);
        INVERSE.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), minecraft.getWindow());
        if (invViewProj != null) {
            invViewProj.set(INVERSE);
        }
        BandFogProfile.SkyStyle sky = BandFogProfile.skyForLane(farLaneName);
        if (zenith != null) {
            zenith.set(sky.zenithRed(), sky.zenithGreen(), sky.zenithBlue());
        }
        if (horizon != null) {
            horizon.set(sky.horizonRed(), sky.horizonGreen(), sky.horizonBlue());
        }
        if (stars != null) {
            stars.set(sky.starDensity());
        }
        writeRiftUniforms();

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        try {
            shader.apply();
            buffer.bind();
            try {
                buffer.draw();
            } finally {
                VertexBuffer.unbind();
            }
            VolumetricRiftState.noteFarDraw();
        } finally {
            shader.clear();
            RenderSystem.depthMask(true);
        }
        return true;
    }

    private static void writeRiftUniforms() {
        if (riftMode != null) {
            riftMode.set(VolumetricRiftState.mode());
        }
        float[] plane = VolumetricRiftState.plane();
        if (riftPlane != null) {
            riftPlane.set(plane[0], plane[1], plane[2], plane[3]);
        }
        float[] center = VolumetricRiftState.center();
        if (riftCenter != null) {
            riftCenter.set(center[0], center[1], center[2], center[3]);
        }
        float[] radii = VolumetricRiftState.radii();
        if (riftRadii != null) {
            riftRadii.set(radii[0], radii[1], radii[2], radii[3]);
        }
        float[] style = VolumetricRiftState.style();
        if (riftStyle != null) {
            riftStyle.set(style[0], style[1], style[2], style[3]);
        }
        float[] cam = VolumetricRiftState.cam();
        if (riftCam != null) {
            riftCam.set(cam[0], cam[1], cam[2], cam[3]);
        }
    }

    private static void bindUniforms(ShaderInstance shader) {
        if (shader == cached) {
            return;
        }
        cached = shader;
        invViewProj = shader.getUniform("u_InvViewProj");
        zenith = shader.getUniform("u_Zenith");
        horizon = shader.getUniform("u_Horizon");
        stars = shader.getUniform("u_Stars");
        riftMode = shader.getUniform("u_RiftMode");
        riftPlane = shader.getUniform("u_RiftPlane");
        riftCenter = shader.getUniform("u_RiftCenter");
        riftRadii = shader.getUniform("u_RiftRadii");
        riftStyle = shader.getUniform("u_RiftStyle");
        riftCam = shader.getUniform("u_RiftCam");
        if (invViewProj == null || zenith == null || horizon == null) {
            LOGGER.warn("[bandrift] band_far_sky uniforms missing (InvViewProj={} Zenith={} Horizon={}); "
                            + "the far sky will render with the JSON defaults",
                    invViewProj != null, zenith != null, horizon != null);
        }
    }

    @Nullable
    private static VertexBuffer quad() {
        if (quad != null) {
            return quad;
        }
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(-1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, -1.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F);
        builder.addVertex(-1.0F, 1.0F, 0.0F);
        MeshData data = builder.build();
        if (data == null) {
            return null;
        }
        VertexBuffer created = new VertexBuffer(VertexBuffer.Usage.STATIC);
        created.bind();
        try {
            created.upload(data);
        } finally {
            VertexBuffer.unbind();
        }
        quad = created;
        return created;
    }

    /** Releases the quad; render thread only, called on level unload. */
    public static void close() {
        if (quad != null) {
            quad.close();
            quad = null;
        }
        cached = null;
        invViewProj = null;
        zenith = null;
        horizon = null;
        stars = null;
        riftMode = null;
        riftPlane = null;
        riftCenter = null;
        riftRadii = null;
        riftStyle = null;
        riftCam = null;
    }
}
