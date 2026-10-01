package dimblend.experience.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.LevelChunk;

/** GameTest 用的世界侧群系改写（原版类型，无第三方依赖）。 */
final class GameTestBiomes {

    /**
     * 把 {@code around} 所在及相邻 3×3 区块整块改成同一群系
     *（{@code BiomeManager} 取样会跨相邻 quart，只改单区块会读到混合结果）。
     */
    static void overwrite(ServerLevel level, BlockPos around, ResourceKey<Biome> biome) {
        Holder<Biome> holder = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(biome);
        ChunkPos center = new ChunkPos(around);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk chunk = level.getChunk(center.x + dx, center.z + dz);
                chunk.fillBiomesFromNoise((x, y, z, sampler) -> holder, Climate.empty());
            }
        }
    }

    static ResourceKey<Biome> keyOf(Holder<Biome> holder) {
        return holder.unwrapKey().orElseThrow();
    }

    private GameTestBiomes() {
    }
}
