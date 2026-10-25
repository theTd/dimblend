package dimblend.spyglass.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureOutline;
import dimblend.spyglass.structure.StructureShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws the real, textured block meshes of streamed structure shapes while scoping.
 *
 * <p><b>When.</b> Only while {@link SpyglassScopeGate#isScoping} is true and the client's reveal
 * distance is known ({@link SpyglassClientState#currentStructures}). A structure is shown only if its
 * mesh was uploaded and drawn; there is no wireframe fallback. The mesh build requests and the
 * per-frame selection (frustum culling, {@link ShapeMeshStore#markSelected}/{@link
 * ShapeMeshStore#markDrawn}/{@link ShapeMeshStore#markUsed}) happen in exactly one place: the
 * {@code AFTER_SKY} handler.
 *
 * <p>Line numbers are vanilla 1.21.1 (Mojmap) unless marked NeoForge-patched (21.1.249).
 *
 * <p><b>Two passes.</b> The frame projection's far plane is only {@code 4 × renderDistance}
 * ({@code GameRenderer.java:982-984}), so a mesh can be classified by where it is relative to the
 * loaded terrain ({@link ShapePassSelector}):
 *
 * <ul>
 *   <li><b>FAR</b> (outside the loaded terrain, or past the frame's depth range): the original
 *       after-sky placement. {@code RenderLevelStageEvent.Stage.AFTER_SKY} is posted right after {@code
 *       renderSky} and before the terrain fog setup and the terrain passes (NeoForge-patched
 *       {@code LevelRenderer.java:963-976}; vanilla {@code renderSky} at {@code :947}, terrain fog at
 *       {@code :949}, solid/cutout_mipped/cutout at {@code :955-957}), into the frame's main render
 *       target (the frame's color + depth clear is {@code LevelRenderer.java:942}; the sky never
 *       writes depth, {@code LevelRenderer.java:1561,1656}). It gets its own near/far
 *       ({@link ShapeDepthRange}) and clears the depth buffer afterwards, which restores exactly the
 *       depth state the terrain pass expects. Real terrain therefore draws over those meshes wherever
 *       it exists.</li>
 *   <li><b>OVERLAY</b> (inside the loaded terrain): drawn at
 *       {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES}, i.e. after all terrain, entities and
 *       particles wrote the depth buffer. It uses the event's own projection and model-view
 *       unmodified, so the depth values match the terrain already in the buffer, tests with
 *       {@code GL_LEQUAL}, writes depth and applies vanilla's {@code POLYGON_OFFSET_LAYERING}
 *       ({@code RenderStateShard.java:274-282}) so its faces win against coplanar terrain. The real
 *       terrain fog is mixed in at a configurable share ({@link ShapePassFog}, {@link
 *       ShapeFogStrength}): the mesh stays crisp before the fog start, like the terrain, and reaches
 *       at most the near share inside and past the band instead of turning into flat fog color.
 *       There is no depth clear: the overlay is meant to cover the real building, and whatever
 *       renders after it (clouds, weather, GUI) owns the depth state it sets up itself.</li>
 * </ul>
 *
 * <p><b>Fog.</b> Both passes reinstall the last {@code FOG_TERRAIN} start/end/shape
 * ({@link SpyglassRevealState}) and this frame's level fog color, then upload a per-mesh
 * {@code FogColor} alpha ({@link ShapePassFog}). The share is {@code nearFogStrength} for overlay
 * meshes and ramps from it at the fog end to {@code farFogStrength} at 1024 blocks for far meshes
 * ({@link ShapeFogStrength}); zero restores the old crisp look, one is the real terrain fog.
 * {@link SpyglassClientConfig} is read on every pass, so in-game edits take effect immediately.
 *
 * <p>The overlay list is computed in the {@code AFTER_SKY} pass and consumed by the
 * {@code AFTER_PARTICLES} pass of the <b>same</b> frame (guarded by the {@link ShapeMeshStore} frame
 * id), so a stale list is never drawn.
 *
 * <p><b>Projection.</b> The far pass keeps the frame projection's FOV (incl. the spyglass zoom),
 * aspect and view bobbing but replaces the depth range ({@link SpyglassProjection#withDepthRange})
 * with {@link ShapeDepthRange}: near at half the distance of the nearest drawn far mesh, far beyond
 * the farthest one. A 24-bit depth buffer at near 0.05 resolves roughly one block at 1000 blocks; at
 * near 128 it is below a millimetre, which is why far meshes need their own range. The model-view is
 * the frame's (camera rotation) for both passes; the camera-relative mesh origin goes into the chunk
 * shader's {@code ChunkOffset}, as vanilla does per section.
 *
 * <p><b>Drawing.</b> Per layer in vanilla order (solid, cutout_mipped, cutout, translucent, tripwire -
 * {@code RenderType.java:745}): the layer's own {@code setupRenderState()} (shader, block atlas,
 * lightmap, depth, blending), then the same loop as {@code LevelRenderer.renderSectionLayer}
 * ({@code LevelRenderer.java:1228-1297}): default uniforms + {@code apply()} once, then per mesh
 * {@code ChunkOffset} + {@code FogColor} + bind + draw. {@code VertexBuffer.drawWithShader} is not used because it
 * leaves {@code ChunkOffset} at whatever the last section draw set and re-applies the program for
 * every buffer. Opaque layers go nearest mesh first; blended layers farthest first. Translucent and
 * tripwire normally render into extra targets under Fabulous graphics
 * ({@code RenderStateShard.java:303-311}); those are cleared later in the frame, so the main target is
 * bound again after each layer's setup.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class SpyglassShapeRenderer {
    /** Frustum test only: side planes do not depend on the near/far planes; the far plane must reach the shapes. */
    private static final float CULL_NEAR = 0.05F;

    private static final OutlineCulling CULLING = new OutlineCulling();
    private static final Matrix4f CULL_PROJECTION = new Matrix4f();
    private static final Matrix4f PASS_PROJECTION = new Matrix4f();
    private static final ShapePassFog FOG = new ShapePassFog();

    /** Meshes drawn in the AFTER_SKY pass, nearest first. */
    private static final List<UploadedShapeMesh> FAR = new ArrayList<>();
    private static double[] farDistances = new double[16];
    /** Meshes drawn in the AFTER_PARTICLES overlay pass, nearest first. */
    private static final List<UploadedShapeMesh> OVERLAY = new ArrayList<>();
    private static double[] overlayDistances = new double[16];
    /** Frame the overlay list belongs to; {@code Long.MIN_VALUE} means "no valid list". */
    private static long overlayFrame = Long.MIN_VALUE;

    private SpyglassShapeRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            selectAndDrawFar(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            drawOverlayAndPublishLabels(event);
        }
    }

    /**
     * The one selection point of the frame: chooses the drawn meshes, splits them into the far and
     * overlay sets, requests at most one mesh build and draws the far set.
     */
    private static void selectAndDrawFar(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        long frame = ShapeMeshStore.beginFrame(minecraft);
        // Whatever the outcome below, the previous frame's overlay list must never be drawn again.
        overlayFrame = Long.MIN_VALUE;
        OVERLAY.clear();
        FAR.clear();
        if (!SpyglassScopeGate.isScoping(minecraft)) {
            return;
        }
        List<StructureOutline> structures = SpyglassClientState.currentStructures();
        if (structures.isEmpty()) {
            return;
        }

        Vec3 eye = event.getCamera().getPosition();
        SpyglassProjection.withDepthRange(
                event.getProjectionMatrix(), CULL_NEAR, SpyglassProjection.REQUIRED_FAR_PLANE, CULL_PROJECTION);
        CULLING.update(event.getModelViewMatrix(), CULL_PROJECTION, eye);
        double loadedRadius = minecraft.options.getEffectiveRenderDistance() * 16.0;
        float depthFar = minecraft.gameRenderer.getDepthFar();

        double nearest = Double.POSITIVE_INFINITY;
        double farthest = 0.0;
        StructureShape request = null;
        double requestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < structures.size(); i++) {
            Optional<StructureShape> found = SpyglassShapeCache.shapeFor(structures.get(i));
            if (found.isEmpty()) {
                continue;
            }
            StructureShape shape = found.get();
            UploadedShapeMesh mesh = ShapeMeshStore.peek(shape.key());
            boolean current = mesh != null && mesh.revision() == shape.revision();
            if (!current && ShapeMeshStore.canRequest(shape)) {
                // Build what is in view first, nearest first: the scoped view is narrow, so this is
                // what the player is looking at. One request per frame is plenty at frame rate.
                OutlineBox box = mesh != null ? mesh.bounds() : BuiltShapeMesh.meshBounds(shape);
                if (CULLING.isVisible(box)) {
                    double distance = ShapeDepthRange.nearestDistance(box, eye.x, eye.y, eye.z);
                    if (distance < requestDistance) {
                        requestDistance = distance;
                        request = shape;
                    }
                }
            }
            if (mesh == null || mesh.isEmpty()) {
                continue;
            }
            mesh.markSelected(frame);
            if (!CULLING.isVisible(mesh.bounds())) {
                continue;
            }
            mesh.markDrawn(frame);
            ShapeMeshStore.markUsed(shape.key());
            double nearestDistance = ShapeDepthRange.nearestDistance(mesh.bounds(), eye.x, eye.y, eye.z);
            if (ShapePassSelector.isOverlay(mesh.bounds(), eye.x, eye.y, eye.z, loadedRadius, depthFar)) {
                overlayDistances = addSortedByDistance(OVERLAY, overlayDistances, mesh, nearestDistance);
            } else {
                farDistances = addSortedByDistance(FAR, farDistances, mesh, nearestDistance);
                nearest = Math.min(nearest, nearestDistance);
                farthest = Math.max(
                        farthest, ShapeDepthRange.farthestDistance(mesh.bounds(), eye.x, eye.y, eye.z));
            }
        }
        if (request != null && minecraft.level != null) {
            ShapeMeshStore.request(request, minecraft.level, eye, minecraft.getBlockRenderer());
        }
        // Labels are published by the AFTER_PARTICLES pass from the mesh stamps, but only when the
        // selection pass of the same frame actually ran.
        overlayFrame = frame;
        if (FAR.isEmpty()) {
            return;
        }

        SpyglassProjection.withDepthRange(
                event.getProjectionMatrix(),
                ShapeDepthRange.nearPlane(nearest),
                ShapeDepthRange.farPlane(farthest),
                PASS_PROJECTION);
        drawFar(minecraft, event.getModelViewMatrix(), PASS_PROJECTION, eye);
    }

    /** The overlay pass: draws the list selected earlier in this frame, then publishes labels. */
    private static void drawOverlayAndPublishLabels(RenderLevelStageEvent event) {
        // Every level pass replaces the previous frame's label hand-off, also when nothing is drawn.
        ShownOutlineFrame.publish(null);
        Minecraft minecraft = Minecraft.getInstance();
        long frame = ShapeMeshStore.frame();
        if (overlayFrame != frame) {
            // No selection pass in this frame (not scoping): never draw the previous frame's list.
            OVERLAY.clear();
            FAR.clear();
            return;
        }
        overlayFrame = Long.MIN_VALUE;
        try {
            if (!SpyglassScopeGate.isScoping(minecraft)) {
                return;
            }
            Vec3 eye = event.getCamera().getPosition();
            if (!OVERLAY.isEmpty()) {
                drawOverlay(minecraft, event.getModelViewMatrix(), event.getProjectionMatrix(), eye);
            }
            List<StructureOutline> shown = drawnStructures(frame);
            if (!shown.isEmpty()) {
                // The event's model-view is the camera rotation and its projection carries the
                // scoped FOV and view bobbing (the far plane does not move screen x/y).
                Matrix4f worldToClip = new Matrix4f(event.getProjectionMatrix())
                        .mul(event.getModelViewMatrix());
                ShownOutlineFrame.publish(
                        new ShownOutlineFrame(shown, worldToClip, eye.x, eye.y, eye.z));
            }
        } finally {
            OVERLAY.clear();
            FAR.clear();
        }
    }

    /** Structures whose real mesh was actually drawn this frame, in either pass. */
    private static List<StructureOutline> drawnStructures(long frame) {
        List<StructureOutline> structures = SpyglassClientState.currentStructures();
        List<StructureOutline> shown = null;
        for (int i = 0; i < structures.size(); i++) {
            StructureOutline structure = structures.get(i);
            if (ShapeMeshStore.meshUse(structure.key()) != ShapeMeshStore.MeshUse.DRAWN) {
                continue;
            }
            if (shown == null) {
                shown = new ArrayList<>(structures.size() - i);
            }
            shown.add(structure);
        }
        return shown == null ? List.of() : shown;
    }

    /**
     * Far pass: dedicated depth range, the real terrain fog at the far share, depth buffer cleared
     * afterwards.
     */
    private static void drawFar(Minecraft minecraft, Matrix4f modelView, Matrix4f projection, Vec3 eye) {
        float fogEnd = SpyglassRevealState.fogEndBlocks();
        float nearStrength = (float) SpyglassClientConfig.nearFogStrength();
        float farStrength = (float) SpyglassClientConfig.farFogStrength();
        FOG.begin((float) SpyglassRevealState.revealDistanceBlocks(), fogEnd, SpyglassRevealState.fogShape());
        try {
            drawLayers(minecraft, FAR, modelView, projection, eye, false, fogEnd, nearStrength, farStrength);
        } finally {
            FOG.end();
        }
        // Hand the depth buffer back to the terrain pass exactly as the sky left it: cleared. Terrain
        // drawn next then covers the far meshes wherever real terrain exists. glClear honours the
        // depth write mask, so make sure it is on.
        RenderSystem.depthMask(true);
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    /**
     * Overlay pass: the event's own matrices, depth {@code LEQUAL} with writes, polygon offset and the
     * real terrain fog at the near share, so the structure is drawn over the fogged terrain that is
     * already in the depth buffer with a tunable amount of that same fog. No depth clear: the overlay
     * is meant to cover the real building.
     */
    private static void drawOverlay(
            Minecraft minecraft, Matrix4f modelView, Matrix4f projection, Vec3 eye) {
        RenderSystem.assertOnRenderThread();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        // Vanilla POLYGON_OFFSET_LAYERING (RenderStateShard.java:274-282).
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        RenderSystem.enablePolygonOffset();
        float fogEnd = SpyglassRevealState.fogEndBlocks();
        float nearStrength = (float) SpyglassClientConfig.nearFogStrength();
        float farStrength = (float) SpyglassClientConfig.farFogStrength();
        FOG.begin((float) SpyglassRevealState.revealDistanceBlocks(), fogEnd, SpyglassRevealState.fogShape());
        try {
            drawLayers(minecraft, OVERLAY, modelView, projection, eye, true, fogEnd, nearStrength, farStrength);
        } finally {
            FOG.end();
            RenderSystem.disablePolygonOffset();
            RenderSystem.polygonOffset(0.0F, 0.0F);
            // A chunk layer's clearRenderState leaves the depth test off, the function at LEQUAL and
            // writes on (RenderStateShard.java:428-438); restore exactly that, which is also where the
            // particle pass leaves the frame.
            RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
        }
    }

    private static void drawLayers(
            Minecraft minecraft,
            List<UploadedShapeMesh> meshes,
            Matrix4f modelView,
            Matrix4f projection,
            Vec3 eye,
            boolean overlay,
            float fogEnd,
            float nearStrength,
            float farStrength) {
        List<RenderType> layers = ShapeMeshLayers.order();
        int count = meshes.size();
        for (int layer = 0; layer < layers.size(); layer++) {
            if (!anyMeshHas(meshes, layer)) {
                continue;
            }
            drawLayer(minecraft, meshes, layers.get(layer), layer, count, modelView, projection, eye,
                    overlay, fogEnd, nearStrength, farStrength);
        }
    }

    /**
     * Draws one render layer for every mesh that has geometry in it. The per-mesh fog share is
     * uploaded right where {@code ChunkOffset} is set; both uniforms are reset after the loop.
     */
    private static void drawLayer(
            Minecraft minecraft,
            List<UploadedShapeMesh> meshes,
            RenderType type,
            int layer,
            int count,
            Matrix4f modelView,
            Matrix4f projection,
            Vec3 eye,
            boolean overlay,
            float fogEnd,
            float nearStrength,
            float farStrength) {
        AbstractTexture atlas = null;
        if (type == RenderType.cutoutMipped()) {
            // Same guard NeoForge puts around the cutout_mipped terrain pass (NeoForge-patched
            // LevelRenderer.java:973-975).
            atlas = minecraft.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
            atlas.setBlurMipmap(false, minecraft.options.mipmapLevels().get() > 0);
        }
        type.setupRenderState();
        try {
            if (Minecraft.useShaderTransparency()) {
                // Translucent/tripwire bind the Fabulous translucent/weather target, which is cleared
                // later this frame; the meshes belong in the main target like the sky.
                minecraft.getMainRenderTarget().bindWrite(false);
            }
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) {
                return;
            }
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
            shader.apply();
            Uniform chunkOffset = shader.CHUNK_OFFSET;
            boolean farthestFirst = ShapeMeshLayers.isBlended(layer);
            for (int k = 0; k < count; k++) {
                UploadedShapeMesh mesh = meshes.get(farthestFirst ? count - 1 - k : k);
                VertexBuffer buffer = mesh.layer(layer);
                if (buffer == null) {
                    continue;
                }
                float fogStrength = overlay
                        ? (float) ShapeFogStrength.overlay(nearStrength)
                        : (float) ShapeFogStrength.far(nearStrength, farStrength, fogEnd,
                                ShapeFogStrength.centerDistance(mesh.bounds(), eye.x, eye.z));
                if (chunkOffset != null) {
                    chunkOffset.set(
                            (float) (mesh.originX() - eye.x),
                            (float) (mesh.originY() - eye.y),
                            (float) (mesh.originZ() - eye.z));
                    chunkOffset.upload();
                }
                FOG.setStrength(shader, fogStrength);
                buffer.bind();
                buffer.draw();
            }
            if (chunkOffset != null) {
                chunkOffset.set(0.0F, 0.0F, 0.0F);
            }
            FOG.resetStrength(shader);
            shader.clear();
            VertexBuffer.unbind();
        } finally {
            type.clearRenderState();
            if (atlas != null) {
                atlas.restoreLastBlurMipmap();
            }
        }
    }

    private static boolean anyMeshHas(List<UploadedShapeMesh> meshes, int layer) {
        for (int i = 0; i < meshes.size(); i++) {
            if (meshes.get(i).layer(layer) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Insertion into one of the per-frame draw lists, nearest first; the lists are short (scoped
     * view). Returns the possibly grown distance array.
     */
    private static double[] addSortedByDistance(
            List<UploadedShapeMesh> meshes,
            double[] distances,
            UploadedShapeMesh mesh,
            double distance) {
        int size = meshes.size();
        if (distances.length <= size) {
            distances = Arrays.copyOf(distances, distances.length * 2);
        }
        int index = size;
        while (index > 0 && distances[index - 1] > distance) {
            distances[index] = distances[index - 1];
            index--;
        }
        distances[index] = distance;
        meshes.add(index, mesh);
        return distances;
    }
}
