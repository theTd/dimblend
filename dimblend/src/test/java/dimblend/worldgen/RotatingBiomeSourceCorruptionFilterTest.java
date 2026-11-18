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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RotatingBiomeSource} answers {@code minecraft:end_highlands} instead of BOP's
 * {@code biomesoplenty:end_corruption} inside the end corridor.
 *
 * <p>BOP registers {@code end_corruption} only in the TerraBlender highlands pool (weight 3), so
 * the filter applies to every delegate answer and falls back to vanilla highlands. The window is
 * {@code |quartZ| <= 35}: the biome itself must sit outside {@code |z| <= 128}, and BOP features
 * spread up to 7 blocks inward from the nearest allowed biome (see report r5 §8).</p>
 *
 * <p>Like {@code RotatingBiomeSourceBarrenTest}, this source is built without a chunk generator
 * and without registry access: the highlands holder comes from the End source's own biome set,
 * and a missing holder must degrade to the delegate answer instead of throwing.</p>
 */
class RotatingBiomeSourceCorruptionFilterTest {
    private static final int BAND_SIZE = BandIndex.DEFAULT_BAND_SIZE;
    private static final int BARREN_REGION = 40;

    private static final HolderOwner<Biome> OWNER = new HolderOwner<>() {
    };
    private static final ResourceKey<Biome> CORRUPTION_KEY = ResourceKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("biomesoplenty", "end_corruption"));
    private static final Holder<Biome> CORRUPTION = holder(CORRUPTION_KEY);
    private static final Holder<Biome> HIGHLANDS = holder(Biomes.END_HIGHLANDS);
    private static final Holder<Biome> MIDLANDS = holder(Biomes.END_MIDLANDS);
    private static final Holder<Biome> BARRENS = holder(Biomes.END_BARRENS);

    @AfterEach
    void detachFakeOverrides() {
        RegionOverrides.Impl.install(RegionOverrides.Impl.NONE);
    }

    @Test
    void corruptionInsideTheWindowBecomesEndHighlands() {
        RotatingBiomeSource source = sourceOf(CORRUPTION, List.of(CORRUPTION, HIGHLANDS));

        for (int quartZ : new int[]{0, -35, 35}) {
            assertSame(HIGHLANDS, source.getNoiseBiome(0, 16, quartZ, null),
                    "|quartZ| <= 35 内不得出现 end_corruption，quartZ=" + quartZ);
        }
    }

    @Test
    void corruptionOutsideTheWindowKeepsTheDelegateAnswer() {
        RotatingBiomeSource source = sourceOf(CORRUPTION, List.of(CORRUPTION, HIGHLANDS));

        for (int quartZ : new int[]{-36, 36}) {
            assertSame(CORRUPTION, source.getNoiseBiome(0, 16, quartZ, null),
                    "|quartZ| > 35 的腐化群系保持不变，quartZ=" + quartZ);
        }
    }

    @Test
    void otherBiomesInsideTheWindowAreUntouched() {
        RotatingBiomeSource source = sourceOf(MIDLANDS, List.of(MIDLANDS, HIGHLANDS));

        assertSame(MIDLANDS, source.getNoiseBiome(0, 16, 0, null),
                "过滤只认 biomesoplenty:end_corruption");
        assertSame(MIDLANDS, source.getNoiseBiome(0, 16, -35, null));
    }

    @Test
    void missingEndHighlandsFallsBackToTheDelegateWithoutThrowing() {
        // A mock or End-less layout: nothing offers minecraft:end_highlands.
        RotatingBiomeSource source = sourceOf(CORRUPTION, List.of(CORRUPTION));

        Holder<Biome> answer = source.getNoiseBiome(0, 16, 0, null);
        assertSame(CORRUPTION, answer, "找不到 end_highlands 时退回 delegate 的答案，而不是抛异常");
        assertFalse(answer.is(Biomes.END_HIGHLANDS));

        // The absence is remembered, not re-scanned into a different answer.
        assertSame(CORRUPTION, source.getNoiseBiome(0, 0, 35, null));
    }

    @Test
    void forcedBarrenRegionStillAnswersEndBarrens() {
        force(BARREN_REGION);
        RotatingBiomeSource source = sourceOf(CORRUPTION, List.of(CORRUPTION, HIGHLANDS, BARRENS));

        assertSame(BARRENS, source.getNoiseBiome(quart(BARREN_REGION * BAND_SIZE), 16, 0, null),
                "barren 分支先于 delegate，也不受腐化过滤影响");
        assertTrue(BARRENS.is(Biomes.END_BARRENS));
    }

    @Test
    void forcedBarrenRegionWithoutEndBarrensStillFiltersCorruption() {
        force(BARREN_REGION);
        RotatingBiomeSource source = sourceOf(CORRUPTION, List.of(CORRUPTION, HIGHLANDS));

        assertSame(HIGHLANDS, source.getNoiseBiome(quart(BARREN_REGION * BAND_SIZE), 16, 0, null),
                "没有 end_barrens 可退回时，delegate 的腐化答案仍要被过滤");
    }

    /** One stub source that answers {@code answer} everywhere and offers exactly {@code offered}. */
    private static RotatingBiomeSource sourceOf(Holder<Biome> answer, List<Holder<Biome>> offered) {
        return new RotatingBiomeSource(
                List.of(new StubBiomeSource(answer, offered)), BAND_SIZE);
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

    /** Answers a fixed biome everywhere and offers exactly the given list. */
    private static final class StubBiomeSource extends BiomeSource {
        private final Holder<Biome> answer;
        private final List<Holder<Biome>> offered;
        private final MapCodec<StubBiomeSource> codec = MapCodec.unit(this);

        private StubBiomeSource(Holder<Biome> answer, List<Holder<Biome>> offered) {
            this.answer = answer;
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
            return this.answer;
        }
    }
}
