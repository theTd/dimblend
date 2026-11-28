package dimblend.client.mirage;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Bakes one mirage section into up to three {@link MirageSectionMesh} layers.
 *
 * <p>This is the same three-branch loop vanilla's {@code SectionCompiler} uses (fluid, model,
 * block entity — entity rendering is intentionally absent, a mirage carries no block entities),
 * but it cannot reuse {@code SectionCompiler} itself: the compiler needs a {@code RenderChunkRegion}
 * with package-private constructors and a {@code RenderChunk} ring that only the real chunk
 * pipeline owns. {@code BlockRenderDispatcher.renderBatched} / {@code renderLiquid} are public and
 * produce exactly the same {@code DefaultVertexFormat.BLOCK} quads.</p>
 *
 * <p>Vertices are written in section-local 0..16 space (pose translated by the section-relative
 * block position, the block pos itself is the real world position so seeds and tint lookups are
 * correct), and {@code u_MirageOrigin} re-adds the section origin at draw time. One section per
 * call keeps the caller's frame budget granular.</p>
 */
public final class MirageSectionBaker {
    private static final long SEED = 0L;

    private MirageSectionBaker() {
    }

    /** Bakes one section in place; replaces that section's buffers. Render thread only. */
    public static void bake(ClientMirageChunk chunk, int sectionIndex) {
        MirageSectionMesh mesh = chunk.meshFor(sectionIndex);
        if (mesh == null) {
            return;
        }
        // One flood per section, before any face samples the light: this is what turns the
        // heightmap step function into the vanilla sideways gradient under overhangs.
        chunk.ensureLight(sectionIndex);
        int originX = chunk.chunkX() << 4;
        int originY = sectionOriginY(chunk, sectionIndex);
        int originZ = chunk.chunkZ() << 4;

        ByteBufferBuilder solidBytes = new ByteBufferBuilder(2048);
        ByteBufferBuilder cutoutBytes = new ByteBufferBuilder(1024);
        ByteBufferBuilder translucentBytes = new ByteBufferBuilder(1024);
        try {
            BufferBuilder solid = new BufferBuilder(solidBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            BufferBuilder cutout = new BufferBuilder(cutoutBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            BufferBuilder translucent = new BufferBuilder(translucentBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            BlockRenderDispatcher renderer = Minecraft.getInstance().getBlockRenderer();
            PoseStack pose = new PoseStack();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            RandomSource random = RandomSource.create(SEED);
            ModelBlockRenderer.enableCaching();
            try {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int worldX = originX + x;
                            int worldY = originY + y;
                            int worldZ = originZ + z;
                            pos.set(worldX, worldY, worldZ);
                            BlockState state = chunk.getBlockState(pos);
                            if (state.isAir()) {
                                continue;
                            }
                            FluidState fluid = state.getFluidState();
                            if (!fluid.isEmpty()) {
                                RenderType layer = ItemBlockRenderTypes.getRenderLayer(fluid);
                                renderer.renderLiquid(pos, chunk, layer == RenderType.translucent()
                                        ? translucent : solid, state, fluid);
                            }
                            if (state.getRenderShape() != RenderShape.MODEL) {
                                continue;
                            }
                            RenderType layer = ItemBlockRenderTypes.getChunkRenderType(state);
                            BufferBuilder buffer = layer == RenderType.translucent()
                                    ? translucent
                                    : layer == RenderType.cutoutMipped() ? cutout : solid;
                            pose.pushPose();
                            pose.translate(x, y, z);
                            renderer.renderBatched(state, pos, chunk, pose, buffer, true, random);
                            pose.popPose();
                        }
                    }
                }
            } finally {
                ModelBlockRenderer.clearCache();
            }
            // build() returns null for an empty layer, which clears that layer's old buffer.
            mesh.accept(MirageSectionMesh.LAYER_SOLID, solid.build());
            mesh.accept(MirageSectionMesh.LAYER_CUTOUT, cutout.build());
            mesh.accept(MirageSectionMesh.LAYER_TRANSLUCENT, translucent.build());
        } finally {
            solidBytes.close();
            cutoutBytes.close();
            translucentBytes.close();
        }
    }

    private static int sectionOriginY(ClientMirageChunk chunk, int sectionIndex) {
        return chunk.getMinBuildHeight() + (sectionIndex << 4);
    }
}
