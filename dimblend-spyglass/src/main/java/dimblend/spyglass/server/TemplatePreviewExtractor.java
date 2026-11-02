package dimblend.spyglass.server;

import com.mojang.serialization.DataResult;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import dimblend.spyglass.structure.StructureShapeCodec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a block shape for a predicted structure from the templates its jigsaw pieces reference.
 *
 * <p>This is the M2 preview path (CRD 0001): no terrain is generated, no chunk is read or written.
 * Every piece must be a {@link PoolElementStructurePiece} whose pool element is a
 * {@link SinglePoolElement} with a data-driven template id; anything else (mineshafts, fortresses,
 * feature-only pieces, runtime templates) returns no shape and the client keeps the wireframe.
 *
 * <p>The transform mirrors vanilla's placement: the template's local block position is rotated by
 * the piece's rotation and offset by the piece's position. Vanilla's
 * {@code SinglePoolElement#getSettings} never sets a mirror, so {@link Mirror#NONE} is exact for
 * jigsaw pieces.
 *
 * <p>Limits, by design: only the first template palette is used; processors, liquid settings, block
 * entities and loot are not applied; the result floats where the terrain would be.
 */
final class TemplatePreviewExtractor {
    static final int MAX_PREVIEWS = 8;

    /** One template block before placement, in template-local coordinates. */
    record TemplateBlock(BlockPos pos, BlockState state) {
    }

    /** One placed block, in world coordinates. */
    record WorldBlock(BlockPos pos, BlockState state) {
    }

    static List<EncodedStructureShape> extract(
            @Nullable PredictionContext context, List<StructureOutline> candidates) {
        if (context == null) {
            return List.of();
        }
        List<EncodedStructureShape> shapes = new ArrayList<>();
        for (StructureOutline outline : candidates) {
            if (shapes.size() >= MAX_PREVIEWS) {
                break;
            }
            if (!outline.predicted()) {
                continue;
            }
            EncodedStructureShape shape;
            try {
                shape = preview(context, outline);
            } catch (RuntimeException exception) {
                // A malformed template must not take the whole scan down; the client keeps the
                // wireframe for this structure.
                shape = null;
            }
            if (shape != null) {
                shapes.add(shape);
            }
        }
        return List.copyOf(shapes);
    }

    private static @Nullable EncodedStructureShape preview(
            PredictionContext context, StructureOutline outline) {
        StructureStart start = context.startFor(outline.key());
        if (start == null || !start.isValid()) {
            return null;
        }
        List<WorldBlock> blocks = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool)) {
                return null;
            }
            if (!(pool.getElement() instanceof SinglePoolElement single)) {
                return null;
            }
            ResourceLocation templateId = templateId(single);
            if (templateId == null) {
                return null;
            }
            StructureTemplate template;
            try {
                template = context.templateManager().getOrCreate(templateId);
            } catch (RuntimeException exception) {
                return null;
            }
            if (template == null) {
                return null;
            }
            addBlocks(
                    blocks,
                    parseTemplate(template.save(new CompoundTag())),
                    pool.getPosition(),
                    pool.getRotation());
        }
        return encode(outline.key(), blocks);
    }

    /** Reads a template's first palette and blocks from the NBT {@link StructureTemplate#save} writes. */
    static List<TemplateBlock> parseTemplate(CompoundTag tag) {
        ListTag blockList = tag.getList("blocks", Tag.TAG_COMPOUND);
        if (blockList.isEmpty()) {
            return List.of();
        }
        ListTag paletteTag;
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);
            if (palettes.isEmpty()) {
                return List.of();
            }
            paletteTag = palettes.getList(0);
        } else {
            paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        }
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            palette[i] = BlockState.CODEC.parse(NbtOps.INSTANCE, paletteTag.get(i)).result().orElse(null);
        }
        List<TemplateBlock> blocks = new ArrayList<>(blockList.size());
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag block = blockList.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            int stateIndex = block.getInt("state");
            if (pos.size() != 3
                    || stateIndex < 0
                    || stateIndex >= palette.length
                    || palette[stateIndex] == null) {
                continue;
            }
            blocks.add(new TemplateBlock(
                    new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), palette[stateIndex]));
        }
        return blocks;
    }

    static BlockPos worldPos(BlockPos local, BlockPos piecePosition, Rotation rotation) {
        return piecePosition.offset(
                StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO));
    }

    /** Structure blocks and jigsaw markers are not part of the building; void and air are invisible. */
    static boolean keep(BlockState state) {
        Block block = state.getBlock();
        return !state.isAir()
                && block != Blocks.STRUCTURE_BLOCK
                && block != Blocks.JIGSAW
                && block != Blocks.STRUCTURE_VOID;
    }

    /** Encodes the placed blocks into the existing shape payload; {@code null} when nothing remains. */
    static @Nullable EncodedStructureShape encode(StructureKey key, List<WorldBlock> blocks) {
        if (blocks.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (WorldBlock block : blocks) {
            BlockPos pos = block.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        List<StructureShapeCodec.BlockEntry> entries = new ArrayList<>(blocks.size());
        for (WorldBlock block : blocks) {
            BlockPos pos = block.pos();
            long index = ((long) (pos.getY() - minY) * sizeZ + (pos.getZ() - minZ)) * sizeX
                    + (pos.getX() - minX);
            entries.add(new StructureShapeCodec.BlockEntry(index, block.state()));
        }
        byte[] body = StructureShapeCodec.encode(
                minX, minY, minZ, sizeX, sizeY, sizeZ, Biomes.PLAINS.location(), entries);
        return EncodedStructureShape.split(key, 0, entries.size(), body);
    }

    private static void addBlocks(
            List<WorldBlock> out, List<TemplateBlock> blocks, BlockPos piecePosition, Rotation rotation) {
        for (TemplateBlock block : blocks) {
            if (keep(block.state())) {
                out.add(new WorldBlock(worldPos(block.pos(), piecePosition, rotation), block.state()));
            }
        }
    }

    /** The data-driven template id of a single pool element; runtime templates encode to nothing. */
    private static @Nullable ResourceLocation templateId(SinglePoolElement element) {
        try {
            DataResult<Tag> encoded = StructurePoolElement.CODEC.encodeStart(NbtOps.INSTANCE, element);
            Tag tag = encoded.result().orElse(null);
            if (tag instanceof CompoundTag compound && compound.contains("location", Tag.TAG_STRING)) {
                return ResourceLocation.tryParse(compound.getString("location"));
            }
        } catch (RuntimeException ignored) {
            // A runtime template (Either.right) cannot be serialized; no preview for this structure.
        }
        return null;
    }

    private TemplatePreviewExtractor() {
    }
}
