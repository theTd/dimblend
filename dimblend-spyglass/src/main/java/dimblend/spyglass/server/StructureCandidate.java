package dimblend.spyglass.server;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

record StructureCandidate(ResourceLocation id, int chunkX, int chunkZ) {
    StructureCandidate {
        Objects.requireNonNull(id, "id");
    }

    long chunkKey() {
        return ChunkPos.asLong(this.chunkX, this.chunkZ);
    }

    long regionKey() {
        return ChunkPos.asLong(Math.floorDiv(this.chunkX, 32), Math.floorDiv(this.chunkZ, 32));
    }
}
