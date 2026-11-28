package dimblend.client.bandrift;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

/**
 * Bridge to the optional Sodium renderer: the direct second terrain pass, the shader injection
 * diagnostics, and the Veil perspective guard.
 *
 * <p>No Sodium type appears in this class's signature or static initialiser. The actual
 * {@code RenderSectionManager.renderLayer} call lives in the nested {@link Bridge}, which is only
 * loaded after {@link #available()} proved Sodium is installed; a pack without Sodium never
 * resolves a single {@code net/caffeinemc/**} class. This mirrors the repository's existing
 * {@code SableBridge} pattern.</p>
 *
 * <p><b>Never call {@code SodiumWorldRenderer.drawChunkLayer} for the second pass.</b> Sable hooks
 * its TAIL to draw every sub-level (train carriages); the direct
 * {@code RenderSectionManager.renderLayer} path has no such hook, so the far pass cannot draw the
 * carriage a second time.</p>
 */
public final class SodiumRiftBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROBE_CLASS = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
    private static final String VEIL_PERSPECTIVE = "foundry.veil.api.client.render.VeilLevelPerspectiveRenderer";

    /** Root Sodium shader currently being parsed, set by {@code ShaderLoaderProbeMixin}. */
    private static final ThreadLocal<ResourceLocation> CURRENT_SHADER = new ThreadLocal<>();

    public static volatile boolean vertexInjected;
    public static volatile boolean fragmentInjected;
    public static volatile String vertexHash = "-";
    public static volatile String fragmentHash = "-";
    public static volatile String vertexAnchor = "-";
    public static volatile String fragmentAnchor = "-";
    public static volatile int programsInjected;
    public static volatile String failure = "";

    private static volatile int presence = -1;
    private static volatile boolean perspectiveChecked;
    private static volatile Method perspectiveMethod;
    private static volatile int mainLightmap;

    private SodiumRiftBridge() {
    }

    /** True when Sodium is installed and its renderer class is loadable. Cached after first call. */
    public static boolean available() {
        int known = presence;
        if (known >= 0) {
            return known == 1;
        }
        boolean found = false;
        try {
            ModList mods = ModList.get();
            found = mods != null && mods.isLoaded("sodium")
                    && Class.forName(PROBE_CLASS, false, SodiumRiftBridge.class.getClassLoader()) != null;
        } catch (Throwable throwable) {
            LOGGER.warn("[bandrift] Sodium probe failed, S2 stays off: {}", throwable.toString());
        }
        presence = found ? 1 : 0;
        return found;
    }

    // ---- shader injection plumbing (mixins) ---------------------------------------------------

    public static void beginShader(ResourceLocation name) {
        CURRENT_SHADER.set(name);
    }

    public static void endShader() {
        CURRENT_SHADER.remove();
    }

    @Nullable
    public static ResourceLocation currentShader() {
        return CURRENT_SHADER.get();
    }

    /** Records a successful injection: which stage, and a stable hash of the rewritten source. */
    public static void noteInjection(boolean vertex, String source) {
        String hash = hash(source);
        if (vertex) {
            if (!vertexInjected) {
                vertexHash = hash;
            }
            vertexInjected = true;
        } else {
            if (!fragmentInjected) {
                fragmentHash = hash;
            }
            fragmentInjected = true;
        }
        programsInjected++;
    }

    /** Records an already-injected source seen again (Sodium compiles one program per pass). */
    public static void notePresent(boolean vertex) {
        if (vertex) {
            vertexInjected = true;
        } else {
            fragmentInjected = true;
        }
    }

    /** Records which anchor variant the injection used, for {@code /dimblendrift diag}. */
    public static void noteAnchor(boolean vertex, boolean primary) {
        if (vertex) {
            vertexAnchor = primary ? "position" : "main-fallback";
        } else {
            fragmentAnchor = primary ? "fog" : "mask-only";
        }
    }

    public static void noteFailure(String message) {
        if (failure.isEmpty()) {
            failure = message;
            LOGGER.warn("[bandrift] {}", message);
        }
    }

    private static String hash(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int i = 0; i < 6 && i < bytes.length; i++) {
                builder.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(source.hashCode());
        }
    }

    /** Records the main lightmap id seen by a Sodium pass (Sodium binds it from shader slot 2). */
    public static void noteMainLightmap(int textureId) {
        if (textureId > 0) {
            mainLightmap = textureId;
        }
    }

    /**
     * The main lightmap texture id, learned from the last Sodium terrain pass. 0 until the first
     * masked-or-not chunk program has run; callers fall back to the band table.
     */
    public static int mainLightmap() {
        return mainLightmap;
    }

    // ---- second pass -------------------------------------------------------------------------

    /**
     * Draws one already-built Sodium terrain pass with the current rift uniforms.
     *
     * @param layer {@link VolumetricRiftState#LAYER_SOLID}, {@code LAYER_CUTOUT} or
     *              {@code LAYER_TRANSLUCENT}
     * @return true when a pass was issued
     */
    public static boolean renderFarLayer(
            int layer, Matrix4fc projection, Matrix4fc modelView, double x, double y, double z) {
        if (!available() || perspectiveRender()) {
            return false;
        }
        return Bridge.render(layer, projection, modelView, x, y, z);
    }

    /**
     * True while Veil's level perspective renderer owns the frame. Reflection on purpose: Veil is
     * JiJ'd into Sable, not a compile dependency, and the method only exists when Veil is active.
     */
    public static boolean perspectiveRender() {
        if (!perspectiveChecked) {
            perspectiveChecked = true;
            try {
                Class<?> type = Class.forName(VEIL_PERSPECTIVE, false, SodiumRiftBridge.class.getClassLoader());
                perspectiveMethod = type.getMethod("isRenderingPerspective");
            } catch (Throwable absent) {
                perspectiveMethod = null;
            }
        }
        Method method = perspectiveMethod;
        if (method == null) {
            return false;
        }
        try {
            Object result = method.invoke(null);
            return result instanceof Boolean flag && flag;
        } catch (Throwable throwable) {
            perspectiveMethod = null;
            return false;
        }
    }

    public static String describe() {
        if (!available()) {
            return "sodium=absent";
        }
        return String.format(Locale.ROOT,
                "sodium=present shaderInjected=%s vshAnchor=%s fshAnchor=%s vshHash=%s fshHash=%s "
                        + "programs=%d failure=%s",
                vertexInjected && fragmentInjected, vertexAnchor, fragmentAnchor,
                vertexHash, fragmentHash, programsInjected,
                failure.isEmpty() ? "-" : failure);
    }

    /** Sodium-typed code, isolated so the outer class stays loadable without Sodium. */
    private static final class Bridge {
        private Bridge() {
        }

        static boolean render(int layer, Matrix4fc projection, Matrix4fc modelView,
                              double x, double y, double z) {
            net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer renderer =
                    net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instance();
            if (renderer == null) {
                return false;
            }
            net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager manager =
                    ((dimblend.mixin.sodium.SodiumWorldRendererAccessor) (Object) renderer)
                            .dimblend$getRenderSectionManager();
            if (manager == null) {
                return false;
            }
            net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass pass =
                    net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.ALL[layer];
            net.caffeinemc.mods.sodium.client.gl.device.RenderDevice.enterManagedCode();
            try {
                manager.renderLayer(
                        new net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices(
                                new org.joml.Matrix4f(projection), new org.joml.Matrix4f(modelView)),
                        pass, x, y, z);
            } finally {
                net.caffeinemc.mods.sodium.client.gl.device.RenderDevice.exitManagedCode();
            }
            VolumetricRiftState.noteFarDraw();
            return true;
        }
    }
}
