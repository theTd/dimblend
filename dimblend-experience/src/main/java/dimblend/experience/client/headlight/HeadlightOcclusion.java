package dimblend.experience.client.headlight;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dimblend.experience.DimBlend;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Block-grid headlight occlusion. Per frame: refresh the world clipmap and the lamps' vehicle grids (CPU,
 * budgeted), then trace one distance map per lamp: for each direction texel of the lamp's cone (tangent-plane
 * projection, {@link #RESOLUTION}^2), a voxel DDA from the lens through the world grid and the vehicle grids
 * stores the distance to the first occluder. Surface and haze passes then test {@code distance(p) <= map} with a
 * 2x2 PCF, so the per-pixel/per-sample cost is four texel fetches instead of a march.
 */
final class HeadlightOcclusion {
    static final int RESOLUTION = 256;
    static final int MAX_LAMPS = 8;
    private static final ResourceLocation TRACE = ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "headlight/occlusion_trace");
    private static HeadlightWorldGrid world;
    private static HeadlightVehicleGrids vehicles;
    private static AdvancedFbo map;
    private static boolean traced;
    private static float tanHalf;

    static boolean allocated() { return world != null; }

    /** Trace cone (tangent of the half angle): slightly wider than the beam so soft edges are covered. */
    static float tanHalf(double fullAngleDegrees) {
        return (float) Math.tan(Math.toRadians(Math.min(fullAngleDegrees * 0.5 + 2.0, 80.0)));
    }

    /** CPU refresh of the grids; returns false when nothing could be prepared. */
    static void update(ClientLevel level, Vec3 camera, List<HeadlightBeam> beams, double range) {
        if (world == null) {
            world = new HeadlightWorldGrid();
            vehicles = new HeadlightVehicleGrids();
        }
        long start = System.nanoTime();
        int reads = world.update(level, camera);
        List<ClientSubLevel> wanted = new ArrayList<>();
        List<Vec3> focus = new ArrayList<>();
        for (var beam : beams) {
            if (beam.owner() != null && !wanted.contains(beam.owner())) {
                wanted.add(beam.owner());
                focus.add(beam.lens());
            }
        }
        // Other vehicles inside a lamp's range sphere also cast shadows (bounded by the slot count).
        if (wanted.size() < HeadlightVehicleGrids.SLOTS) {
            for (var sub : SubLevelContainer.getContainer(level).getAllSubLevels()) {
                if (!(sub instanceof ClientSubLevel client) || wanted.contains(client)) continue;
                var box = client.boundingBox();
                for (var beam : beams) {
                    Vec3 l = beam.lens();
                    double dx = Math.max(Math.max(box.minX() - l.x, l.x - box.maxX()), 0);
                    double dy = Math.max(Math.max(box.minY() - l.y, l.y - box.maxY()), 0);
                    double dz = Math.max(Math.max(box.minZ() - l.z, l.z - box.maxZ()), 0);
                    if (dx * dx + dy * dy + dz * dz <= range * range) {
                        wanted.add(client);
                        focus.add(new Vec3(Math.min(Math.max(l.x, box.minX()), box.maxX()),
                                Math.min(Math.max(l.y, box.minY()), box.maxY()),
                                Math.min(Math.max(l.z, box.minZ()), box.maxZ())));
                        break;
                    }
                }
                if (wanted.size() >= HeadlightVehicleGrids.SLOTS) break;
            }
        }
        reads += vehicles.update(wanted, focus, camera);
        HeadlightDiagnostics.occlusionFill(System.nanoTime() - start, reads);
    }

    /** Traces the per-lamp distance maps; beam uniforms must already be bound on {@code trace}. */
    static boolean trace(ShaderProgram shader, Vec3 camera, int lamps, double angle) {
        traced = false;
        if (shader == null || world == null) return false;
        if (map == null) {
            map = AdvancedFbo.withSize(RESOLUTION, RESOLUTION * MAX_LAMPS).setFormat(GL11.GL_RED, GL30.GL_R32F)
                    .addColorTextureBuffer().setDebugLabel("dimblend headlight occlusion map").build(true);
        }
        tanHalf = tanHalf(angle);
        map.bind(false);
        RenderSystem.viewport(0, 0, RESOLUTION, RESOLUTION * Math.min(lamps, MAX_LAMPS));
        RenderSystem.disableBlend();
        shader.setTexture("WorldGrid", GL12.GL_TEXTURE_3D, world.texture());
        shader.setTexture("VehicleGrid", GL12.GL_TEXTURE_3D, vehicles.texture());
        shader.bindSamplers(0);
        shader.getUniformSafe("WorldGridOffset").setVector((float) (camera.x - world.originBlockX()),
                (float) (camera.y - world.originBlockY()), (float) (camera.z - world.originBlockZ()));
        shader.getUniformSafe("WorldGridWrap").setVectorI(
                HeadlightOcclusionMath.wrap(world.originBlockX(), HeadlightWorldGrid.SIZE_X),
                HeadlightOcclusionMath.wrap(world.originBlockY(), HeadlightWorldGrid.SIZE_Y),
                HeadlightOcclusionMath.wrap(world.originBlockZ(), HeadlightWorldGrid.SIZE_Z));
        shader.getUniformSafe("VehicleCount").setInt(vehicles.count());
        for (int i = 0; i < vehicles.count(); i++) {
            shader.getUniformSafe("VehicleTransform[" + i + "]").setMatrix(vehicles.transform(i));
            int[] size = vehicles.size(i);
            shader.getUniformSafe("VehicleSize[" + i + "]").setVectorI(size[0], size[1], size[2]);
            shader.getUniformSafe("VehicleSlot[" + i + "]").setInt(vehicles.slot(i));
        }
        bindLookupUniforms(shader, true);
        try {
            VeilRenderSystem.drawScreenQuad();
        } finally {
            shader.removeTexture("WorldGrid");
            shader.removeTexture("VehicleGrid");
            unbind3d();
        }
        traced = true;
        return true;
    }

    private static void unbind3d() {
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int i = 0; i < 4; i++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        }
        RenderSystem.activeTexture(activeTexture);
    }

    private static void bindLookupUniforms(ShaderProgram shader, boolean enabled) {
        shader.getUniformSafe("OcclusionEnabled").setInt(enabled ? 1 : 0);
        shader.getUniformSafe("OcclusionResolution").setInt(RESOLUTION);
        shader.getUniformSafe("OcclusionTanHalf").setFloat(tanHalf);
    }

    /**
     * Adds the distance map to a lookup program (surface/fog); without a map this frame visibility is 1. The
     * sampler always gets a texture ({@code placeholder} when disabled, never sampled): Veil assigns texture units
     * in sampler order, and an unset sampler shifted the other programs' samplers onto the wrong textures.
     */
    static void bindLookup(ShaderProgram shader, int placeholder) {
        boolean enabled = traced && map != null;
        shader.setTexture("OcclusionSampler", GL11.GL_TEXTURE_2D,
                enabled ? map.getColorTextureAttachment(0).getId() : placeholder);
        bindLookupUniforms(shader, enabled);
    }

    static void unbindLookup(ShaderProgram shader) {
        shader.removeTexture("OcclusionSampler");
    }

    /** Marks the frame as untraced (option off, no beams, or a failed trace). */
    static void skip() { traced = false; }

    // ---- invalidation (client thread) ----

    static void blockChanged(LevelChunk chunk, BlockPos pos, BlockState state) {
        if (world == null) return;
        var sub = Sable.HELPER.getContainingClient(chunk.getPos().x, chunk.getPos().z);
        if (sub == null) world.blockChanged(pos, state);
        else if (sub instanceof ClientSubLevel client) vehicles.blockChanged(client, pos, state);
    }

    static void onChunkLoad(ChunkEvent.Load event) { chunkChanged(event); }

    static void onChunkUnload(ChunkEvent.Unload event) { chunkChanged(event); }

    private static void chunkChanged(ChunkEvent event) {
        if (world == null || event.getLevel() != Minecraft.getInstance().level) return;
        var pos = event.getChunk().getPos();
        var sub = Sable.HELPER.getContainingClient(pos.x, pos.z);
        if (sub == null) world.chunkChanged(pos.x, pos.z);
        else if (sub instanceof ClientSubLevel client) vehicles.invalidate(client);
    }

    static void close() {
        if (world != null) world.close();
        if (vehicles != null) vehicles.close();
        if (map != null) map.free();
        world = null;
        vehicles = null;
        map = null;
        traced = false;
        HeadlightOccluders.clear();
    }

    private HeadlightOcclusion() {}
}
