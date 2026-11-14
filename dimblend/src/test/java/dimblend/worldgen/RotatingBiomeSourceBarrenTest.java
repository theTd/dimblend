package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.MapCodec;
import dimblend.api.RegionOverrides;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RotatingBiomeSource} answers {@code minecraft:end_barrens} for every position inside a
 * region another mod forced to {@link RegionOverrides.Override#END_BARREN}.
 *
 * <p>Why it matters beyond the saved palette: the biome source is asked outside chunk generation
 * too — structure placement state, {@code findNearestMapStructure}, spawn-rule checks, ungenerated
 * client positions. An End highlands/midlands answer there would keep end-city/chorus eligibility
 * alive in a region the chase turned into an empty island field (requirements §3.3, §4).
 *
 * <p>The source is built without a {@link RotatingChunkGenerator} and without registry access on
 * purpose: the barren answer must come from the End source's own biome set, must not depend on the
 * seeded layout, and must degrade to the delegate answer — not throw — when no source offers
 * {@code end_barrens} (a mock or End-less layout).
 */
class RotatingBiomeSourceBarrenTest {
    private static final int BAND_SIZE = BandIndex.DEFAULT_BAND_SIZE;
    private static final int BARREN_REGION = 40;

    private static final HolderOwner<Biome> OWNER = new HolderOwner<>() {
    };
    private static final Holder<Biome> BARRENS = holder(Biomes.END_BARRENS);
    private static final Holder<Biome> HIGHLANDS = holder(Biomes.END_HIGHLANDS);
    private static final Holder<Biome> SMALL_ISLANDS = holder(Biomes.SMALL_END_ISLANDS);

    @AfterEach
    void detachFakeOverrides() {
        RegionOverrides.Impl.install(RegionOverrides.Impl.NONE);
    }

    @Test
    void forcedRegionAnswersEndBarrensEverywhereInsideIt() {
        force(BARREN_REGION);
        RotatingBiomeSource source = sourceOf(List.of(HIGHLANDS, BARRENS));

        assertSame(BARRENS, source.getNoiseBiome(quartInBarrenRegion(0), 16, 0, null),
                "区域的第一个方块");
        assertSame(BARRENS, source.getNoiseBiome(quartInBarrenRegion(BAND_SIZE - 4), 16, 0, null),
                "区域的最后一个方块");
        assertSame(BARRENS, source.getNoiseBiome(quartInBarrenRegion(255), 100, 0, null),
                "同一区域里的任何高度");
    }

    @Test
    void neighboursOfTheForcedRegionKeepTheirDelegateAnswer() {
        force(BARREN_REGION);
        RotatingBiomeSource source = sourceOf(List.of(HIGHLANDS, BARRENS));

        assertSame(HIGHLANDS,
                source.getNoiseBiome(quart((BARREN_REGION - 1) * BAND_SIZE), 16, 0, null));
        assertSame(HIGHLANDS,
                source.getNoiseBiome(quart((BARREN_REGION + 1) * BAND_SIZE), 16, 0, null));
    }

    @Test
    void sourceWithoutAnEndBarrensHolderFallsBackToTheDelegate() {
        force(BARREN_REGION);
        // A mock/unrelated source: no end_barrens in possibleBiomes(). A forced region must not
        // throw and must not invent a holder from nowhere.
        RotatingBiomeSource source = sourceOf(List.of(HIGHLANDS));

        Holder<Biome> answer = source.getNoiseBiome(quartInBarrenRegion(64), 16, 0, null);
        assertSame(HIGHLANDS, answer, "找不到 end_barrens 时退回 delegate 的答案，而不是抛异常");
        assertFalse(answer.is(Biomes.END_BARRENS));

        // Repeated queries keep answering the delegate: the absent holder is remembered, not
        // re-scanned into a different answer.
        assertSame(HIGHLANDS, source.getNoiseBiome(quartInBarrenRegion(128), 0, 0, null));
    }

    @Test
    void endBarrensIsFoundEvenWhenItIsNotTheFirstOfferedBiome() {
        force(BARREN_REGION);
        RotatingBiomeSource source = sourceOf(List.of(HIGHLANDS, SMALL_ISLANDS, BARRENS));

        assertSame(BARRENS, source.getNoiseBiome(quartInBarrenRegion(0), 0, 0, null),
                "按名字找，不按位置猜");
    }

    @Test
    void noOverrideLeavesEveryRegionOnItsDelegate() {
        RotatingBiomeSource source = sourceOf(List.of(HIGHLANDS, BARRENS));

        assertSame(HIGHLANDS, source.getNoiseBiome(quartInBarrenRegion(0), 16, 0, null));
        assertTrue(RegionOverrides.overrideOf(BARREN_REGION).isEmpty());
    }

    /** One stub source whose whole biome set is {@code offered} and which answers {@code offered.get(0)}. */
    private static RotatingBiomeSource sourceOf(List<Holder<Biome>> offered) {
        return new RotatingBiomeSource(List.of(new StubBiomeSource(offered)), BAND_SIZE);
    }

    /** Quart X inside the forced region, offset 0..bandSize-4 blocks from its west edge. */
    private static int quartInBarrenRegion(int offset) {
        return quart(BARREN_REGION * BAND_SIZE + offset);
    }

    private static int quart(int blockX) {
        return blockX >> 2;
    }

    /** A {@code Holder.Reference} identified by its key: enough for the name lookup, no registry needed. */
    private static Holder<Biome> holder(ResourceKey<Biome> key) {
        return Holder.Reference.createStandAlone(OWNER, key);
    }

    /** Installs a fake api implementation: only {@code regions} are barren. */
    private static void force(int... regions) {
        Set<Integer> barren = new HashSet<>();
        for (int region : regions) {
            barren.add(region);
        }
        RegionOverrides.Impl.install(new RegionOverrides.Impl() {
            @Override
            public boolean forceLane(MinecraftServer server, int region, RegionOverrides.Override override) {
                return false;
            }

            @Override
            public Optional<RegionOverrides.Override> overrideOf(int region) {
                return barren.contains(region)
                        ? Optional.of(RegionOverrides.Override.END_BARREN)
                        : Optional.empty();
            }
        });
    }

    /** Answers its first offered biome everywhere and offers exactly that list. */
    private static final class StubBiomeSource extends BiomeSource {
        private final List<Holder<Biome>> offered;
        private final MapCodec<StubBiomeSource> codec = MapCodec.unit(this);

        private StubBiomeSource(List<Holder<Biome>> offered) {
            this.offered = List.copyOf(offered);
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            return this.codec;
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            return this.offered.stream();
        }

        @Override
        public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
            return this.offered.get(0);
        }
    }
}