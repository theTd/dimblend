package dimblend.radio.compat.sodium;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.AcousticRaycaster;
import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import dimblend.radio.acoustics.terrain.SectionMeshDecoder;
import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Entry point called from the Sodium mixins on chunk-build worker threads. Decodes a finished
 * section's render meshes into acoustic quads and replaces the section's entry in
 * {@link SectionGeometryCache}. The Sodium buffer is only read here; Sodium retains ownership and
 * frees it later on the render thread. Any failure leaves the previous cache entry untouched.
 */
public final class SodiumGeometrySink {
    private static final AtomicBoolean FAILED = new AtomicBoolean();

    /** True if a tee attempt has ever failed (diagnostic for probes). */
    public static boolean failed() {
        return FAILED.get();
    }

    public static void onSectionMeshBuilt(ChunkBuildOutput output, LevelSlice slice) {
        if (TerrainGeometryMode.CURRENT == TerrainGeometryMode.VOXEL) {
            return;
        }
        RenderSection render = output.render;
        long sectionPos = SectionPos.asLong(render.getChunkX(), render.getChunkY(), render.getChunkZ());
        try {
            int originX = render.getOriginX(), originY = render.getOriginY(), originZ = render.getOriginZ();
            dumpRawBuffersIfRequested(output, sectionPos);
            int totalVertices = 0;
            for (BuiltSectionMeshParts parts : output.meshes.values()) {
                totalVertices += parts.getVertexData().getLength() / SectionMeshDecoder.STRIDE;
            }
            float[] vertices = new float[totalVertices * 3];
            byte[] materials = new byte[totalVertices / 4];
            byte[] owners = new byte[totalVertices / 4 * 3];
            int quads = 0;
            for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : output.meshes.entrySet()) {
                // Translucent pass is skipped: its dynamically-resorted buffers use a different
                // quad layout (verified to produce cross-quad smears in the decoder). Stained
                // glass / ice / slime and similar translucent blocks fall to the voxel gap-fill;
                // plain glass is CUTOUT and stays.
                if (entry.getKey().isTranslucent()) {
                    continue;
                }
                quads += SectionMeshDecoder.decode(originX, originY, originZ,
                        entry.getValue().getVertexData().getDirectBuffer(),
                        (cx, cy, cz, nx, ny, nz, ownerOut) -> classify(slice, cx, cy, cz, nx, ny, nz, ownerOut),
                        vertices, materials, owners, quads);
            }
            // A section evicted while this task was in flight must not resurrect its geometry:
            // Sodium discards the result itself, and a stale entry would shadow the empty world.
            if (render.isDisposed()) {
                return;
            }
            SectionGeometryCache.put(sectionPos, originX, originY, originZ,
                    Arrays.copyOf(vertices, quads * 12), Arrays.copyOf(materials, quads),
                    Arrays.copyOf(owners, quads * 3));
            if (SectionGeometryCache.markActive()) {
                DimBlendRadio.LOGGER.info("[radio] render-mesh acoustics active (Sodium chunk mesh tee)");
            }
        } catch (RuntimeException error) {
            if (FAILED.compareAndSet(false, true)) {
                DimBlendRadio.LOGGER.warn("[radio] render-mesh acoustics tee failed; keeping voxel fallback", error);
            }
        }
    }

    /**
     * Resolves the quad's owning block and folds it into the five acoustic material buckets,
     * mirroring the voxel path's classification: air, pure fluids and collision-less blocks
     * (plants, torches) are dropped. The face's solid block usually sits on the negative-normal
     * side; both sides are probed so winding quirks do not lose geometry. The owner block is
     * reported back for emitter-block exclusion.
     * <p>
     * LevelSlice copies only a two-block apron around the section (NEIGHBOR_BLOCK_RADIUS), so
     * quads from models overflowing their section by more than two blocks classify as air and are
     * dropped. Fluids are the only vanilla geometry that overflows farther, and they are dropped
     * regardless.
     */
    private static int classify(LevelSlice slice, double centerX, double centerY, double centerZ,
            double normalX, double normalY, double normalZ, int[] ownerOut) {
        int x = floor(centerX), y = floor(centerY), z = floor(centerZ);
        int material = materialAt(slice, x, y, z);
        if (material < 0) {
            x = floor(centerX - 0.51 * normalX);
            y = floor(centerY - 0.51 * normalY);
            z = floor(centerZ - 0.51 * normalZ);
            material = materialAt(slice, x, y, z);
        }
        if (material < 0) {
            x = floor(centerX + 0.51 * normalX);
            y = floor(centerY + 0.51 * normalY);
            z = floor(centerZ + 0.51 * normalZ);
            material = materialAt(slice, x, y, z);
        }
        if (material < 0) {
            return -1;
        }
        ownerOut[0] = x;
        ownerOut[1] = y;
        ownerOut[2] = z;
        return material;
    }

    private static int materialAt(LevelSlice slice, int x, int y, int z) {
        BlockState state = slice.getBlockState(x, y, z);
        if (state.isAir()) {
            return -1;
        }
        if (!state.getFluidState().isEmpty() && state.getRenderShape() != RenderShape.MODEL) {
            return -1; // Pure fluid cell (water/lava): fluids are the medium, not a reflector.
        }
        try {
            if (state.getCollisionShape(slice, new BlockPos(x, y, z), CollisionContext.empty()).isEmpty()) {
                return -1;
            }
        } catch (RuntimeException unsupportedShape) {
            // Modded shapes that need a live Level are treated as solid, as in the voxel path.
        }
        float reflectivity = AcousticRaycaster.reflectivity(state);
        return reflectivity < 0.2 ? 0 : reflectivity < 0.3 ? 1 : reflectivity < 0.5 ? 2
                : reflectivity < 0.8 ? 3 : 4;
    }

    /** Debug hook: -Ddimblend.radio.acoustic.dumpSection=sx,sy,sz dumps raw pass buffers once. */
    private static void dumpRawBuffersIfRequested(ChunkBuildOutput output, long sectionPos) {
        String target = System.getProperty("dimblend.radio.acoustic.dumpSection");
        if (target == null || !target.equals(SectionPos.x(sectionPos) + "," + SectionPos.y(sectionPos)
                + "," + SectionPos.z(sectionPos))) {
            return;
        }
        for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : output.meshes.entrySet()) {
            var buffer = entry.getValue().getVertexData().getDirectBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            String pass = entry.getKey().toString();
            try {
                java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                        "sodium-raw-" + pass.substring(pass.lastIndexOf('.') + 1) + ".bin"), bytes);
                DimBlendRadio.LOGGER.info("[radio] dumped raw {} buffer: {} bytes", pass, bytes.length);
            } catch (java.io.IOException error) {
                DimBlendRadio.LOGGER.warn("[radio] raw dump failed", error);
            }
        }
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private SodiumGeometrySink() { }
}
