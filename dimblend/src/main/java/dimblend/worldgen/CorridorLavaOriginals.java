package dimblend.worldgen;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Stable final-stage provenance, persisted so unloading a proto chunk cannot lose edge decisions. */
public final class CorridorLavaOriginals {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "dimblend");
    private static final Codec<Map<String, BlockState>> CODEC =
            Codec.unboundedMap(Codec.STRING, BlockState.CODEC)
                    .xmap(HashMap::new, states -> states);
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<String, BlockState>>> ORIGINALS =
            ATTACHMENTS.register("corridor_lava_originals", () -> AttachmentType
                    .<Map<String, BlockState>>builder(() -> new HashMap<>()).serialize(CODEC).build());
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<String, BlockState>>> OUTPUTS =
            ATTACHMENTS.register("corridor_lava_outputs", () -> AttachmentType
                    .<Map<String, BlockState>>builder(() -> new HashMap<>()).serialize(CODEC).build());
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> FINISHED =
            ATTACHMENTS.register("corridor_lava_finished", () -> AttachmentType
                    .builder(() -> false).serialize(Codec.BOOL).build());

    private CorridorLavaOriginals() { }

    public static BlockState state(ChunkAccess chunk, BlockPos pos) {
        Map<String, BlockState> states = chunk.getExistingDataOrNull(ORIGINALS.get());
        String key = Long.toString(pos.asLong());
        BlockState saved = states == null ? null : states.get(key);
        // Natural FEATURES are already complete before the first snapshot. Only
        // our own final transactions may have intervened, so the first value wins.
        return saved == null ? chunk.getBlockState(pos) : saved;
    }

    public static void remember(ChunkAccess chunk, BlockPos pos, BlockState original) {
        if (finished(chunk)) return;
        Map<String, BlockState> states = chunk.getData(ORIGINALS.get());
        if (states.putIfAbsent(Long.toString(pos.asLong()), original) == null) {
            chunk.setUnsaved(true);
        }
    }

    public static Set<BlockPos> sources(ChunkAccess chunk) {
        Set<BlockPos> result = new HashSet<>();
        Map<String, BlockState> states = chunk.getExistingDataOrNull(ORIGINALS.get());
        if (states != null) states.forEach((key, state) -> {
            if (state.getFluidState().is(FluidTags.LAVA) && state.getFluidState().isSource()) {
                result.add(BlockPos.of(Long.parseLong(key)));
            }
        });
        return result;
    }

    public static boolean finished(ChunkAccess chunk) {
        return Boolean.TRUE.equals(chunk.getExistingDataOrNull(FINISHED.get()));
    }

    public static void markFinished(ChunkAccess chunk) {
        chunk.setData(FINISHED.get(), true);
    }

    /** Final-stage requests only. Air wins over glass across stable transactions. */
    public static BlockState request(ChunkAccess chunk, BlockPos pos, BlockState replacement) {
        Map<String, BlockState> outputs = chunk.getData(OUTPUTS.get());
        String key = Long.toString(pos.asLong());
        BlockState previous = outputs.get(key);
        if (previous != null && previous.is(Blocks.AIR)) return previous;
        if (previous != replacement) {
            outputs.put(key, replacement);
            chunk.setUnsaved(true);
        }
        return replacement;
    }

    /** Copy before replay: applying a request may touch the underlying attachment. */
    public static Map<BlockPos, BlockState> outputs(ChunkAccess chunk) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        Map<String, BlockState> saved = chunk.getExistingDataOrNull(OUTPUTS.get());
        if (saved != null) saved.forEach((key, state) -> result.put(BlockPos.of(Long.parseLong(key)), state));
        return result;
    }
}
