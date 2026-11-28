package dimblend.client.bandrift;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

/**
 * Registers the four original core shaders of the band-rift work:
 * {@code band_rift} / {@code band_glitch} (Phase-1 membrane and crossing burst) and
 * {@code mirage_terrain} / {@code band_far_sky} (S2 / M1).
 *
 * <p>NeoForge resolves the JSON as {@code assets/<namespace>/shaders/core/<path>.json} and then the
 * {@code vertex}/{@code fragment} entries inside it as <em>full</em> {@link ResourceLocation}s, so
 * every file spells the namespace out ({@code "vertex": "dimblend:mirage_terrain"}) and the
 * {@code .vsh}/{@code .fsh} live under {@code assets/dimblend/shaders/core/}. A plain
 * {@code "mirage_terrain"} would resolve to {@code minecraft:mirage_terrain} and fail.</p>
 *
 * <p>Failure is contained on purpose. {@code GameRenderer.reloadShaders} wraps this event in the
 * vanilla shader-load try/catch and rethrows anything that escapes as "could not reload shaders",
 * i.e. a client crash. Every construction is caught, logged with the shader id, and left null: the
 * affected pass draws nothing, the membrane path still works, and {@code /dimblendrift diag}
 * reports what failed.</p>
 */
public final class BandRiftShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation BAND_RIFT = id("band_rift");
    public static final ResourceLocation BAND_GLITCH = id("band_glitch");
    public static final ResourceLocation MIRAGE_TERRAIN = id("mirage_terrain");
    public static final ResourceLocation BAND_FAR_SKY = id("band_far_sky");

    private static volatile ShaderInstance rift;
    private static volatile ShaderInstance glitch;
    private static volatile ShaderInstance mirageTerrain;
    private static volatile ShaderInstance farSky;
    private static volatile String failure = "not-registered";

    private BandRiftShaders() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("dimblend", path);
    }

    /** Mod-bus listener: {@code dimblend} client initialisation registers this. */
    public static void onRegisterShaders(RegisterShadersEvent event) {
        // A reload closes the previously registered instances before the load callbacks run, so the
        // old references are dead the moment this fires. Drop them first: a failed rebuild must
        // leave "no shader", never a closed one.
        rift = null;
        glitch = null;
        mirageTerrain = null;
        farSky = null;
        failure = "";
        ResourceProvider provider = event.getResourceProvider();
        try {
            event.registerShader(
                    new ShaderInstance(provider, BAND_RIFT, DefaultVertexFormat.POSITION_TEX_COLOR),
                    loaded -> rift = loaded);
        } catch (Exception exception) {
            failure = describe(BAND_RIFT, exception);
            LOGGER.warn("[bandrift] {} — membrane drawing degrades to nothing, /dimblendrift diag reports it", failure, exception);
        }
        try {
            event.registerShader(
                    new ShaderInstance(provider, BAND_GLITCH, DefaultVertexFormat.POSITION_TEX_COLOR),
                    loaded -> glitch = loaded);
        } catch (Exception exception) {
            String described = describe(BAND_GLITCH, exception);
            failure = failure.isEmpty() ? described : failure + "; " + described;
            LOGGER.warn("[bandrift] {} — crossing screen effect degrades to nothing", described, exception);
        }
        try {
            // DefaultVertexFormat.BLOCK is what the mirage baker writes: Position, Color, UV0,
            // UV2 (lightmap) and Normal, the same layout Sodium's chunk vertices use.
            event.registerShader(
                    new ShaderInstance(provider, MIRAGE_TERRAIN, DefaultVertexFormat.BLOCK),
                    loaded -> mirageTerrain = loaded);
        } catch (Exception exception) {
            String described = describe(MIRAGE_TERRAIN, exception);
            failure = failure.isEmpty() ? described : failure + "; " + described;
            LOGGER.warn("[bandrift] {} — mirage terrain disabled, S2 falls back to the membrane", described, exception);
        }
        try {
            event.registerShader(
                    new ShaderInstance(provider, BAND_FAR_SKY, DefaultVertexFormat.POSITION),
                    loaded -> farSky = loaded);
        } catch (Exception exception) {
            String described = describe(BAND_FAR_SKY, exception);
            failure = failure.isEmpty() ? described : failure + "; " + described;
            LOGGER.warn("[bandrift] {} — far-band sky disabled (terrain mask unaffected)", described, exception);
        }
        if (failure.isEmpty()) {
            LOGGER.info("[bandrift] registered {} and {}", BAND_RIFT, BAND_GLITCH);
            LOGGER.info("[bandrift] registered {} and {}", MIRAGE_TERRAIN, BAND_FAR_SKY);
        }
    }

    @Nullable
    public static ShaderInstance rift() {
        return rift;
    }

    @Nullable
    public static ShaderInstance glitch() {
        return glitch;
    }

    @Nullable
    public static ShaderInstance mirageTerrain() {
        return mirageTerrain;
    }

    @Nullable
    public static ShaderInstance farSky() {
        return farSky;
    }

    /**
     * True when S2 can draw: the masked mirage terrain program exists. The far sky is optional
     * (it no-ops when its shader failed), because losing a backdrop must not disable the mask.
     */
    public static boolean s2Ready() {
        return mirageTerrain != null;
    }

    /** One-line shader health for {@code /dimblendrift diag}. */
    public static String status() {
        boolean all = rift != null && glitch != null && mirageTerrain != null && farSky != null;
        if (all) {
            return "ok";
        }
        String parts = "rift=" + (rift != null) + ",glitch=" + (glitch != null)
                + ",mirage=" + (mirageTerrain != null) + ",sky=" + (farSky != null);
        return failure.isEmpty() ? "partial(" + parts + ")" : "partial(" + parts + ")" + failure;
    }

    private static String describe(ResourceLocation id, Exception exception) {
        String message = exception.getMessage();
        return id + " failed: " + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
    }
}
