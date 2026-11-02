package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 地表矿石规则的纯逻辑单测：门控掷骰与绿宝石替换都不需要 Level。
 *
 * <p>测试环境没有 FML 加载上下文，也没有数据包 tag，所以这里手工初始化注册表并按原版
 * 数据绑定 {@link BlockTags} 的矿石 tag；另外注册两块测试专用方块，分别用来覆盖
 * 锌的 id 兜底分支与 {@code c:ores_in_ground/deepslate} tag 分支。
 */
class OrePileRulesTest {
    private static final int ROLL_SAMPLES = 2000;
    private static final TagKey<Block> DEEPSLATE_GROUND =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores_in_ground/deepslate"));

    /** 测试专用锌矿：本环境没有 Create 的注册表，用 id 里的 zinc 覆盖 {@code isZincPath} 兜底。 */
    private static Block zincOre;
    /** 测试专用矿：id 不带 deepslate_ 前缀，只靠绑定进 tag 覆盖深板岩 tag 分支。 */
    private static Block groundProbeOre;

    @BeforeAll
    static void prepareRegistries() throws ReflectiveOperationException {
        // 纯单测没有 FML 模组表，NeoForge 的 feature flag 载入器需要一个非 null 的 LoadingModList。
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        // 只置位完成标志、不跑 Bootstrap.bootStrap()：后者会去读 classpath 里没有的
        // assets/minecraft/lang/en_us.json，在 CreativeModeTabs 校验时失败。
        Field bootstrapped = Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true);
        bootstrapped.setBoolean(null, true);

        // 第一次引用 Blocks 字段会注册整张原版方块表（约 10 秒，每个测试 JVM 只做一次）。
        assertNotNull(Blocks.AIR, "原版方块表应已注册");
        zincOre = registerProbe("zinc_ore");
        groundProbeOre = registerProbe("ground_probe_ore");

        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        tags.put(BlockTags.COAL_ORES, holders(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE));
        tags.put(BlockTags.IRON_ORES, holders(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE));
        tags.put(BlockTags.COPPER_ORES, holders(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
        tags.put(BlockTags.GOLD_ORES, holders(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE));
        tags.put(DEEPSLATE_GROUND, holders(groundProbeOre));
        BuiltInRegistries.BLOCK.bindTags(tags);
    }

    // ---- 五种矿保留原矿 ----

    @Test
    void premiumSurfaceOresKeepTheirOriginalBlock() {
        for (Block ore : List.of(
                Blocks.COAL_ORE,
                Blocks.DEEPSLATE_COAL_ORE,
                Blocks.IRON_ORE,
                Blocks.DEEPSLATE_IRON_ORE,
                Blocks.COPPER_ORE,
                Blocks.DEEPSLATE_COPPER_ORE,
                Blocks.GOLD_ORE,
                Blocks.DEEPSLATE_GOLD_ORE,
                zincOre)) {
            OreConfiguration pile = pile(ore);
            String name = idOf(ore);
            assertTrue(OrePileRules.isSurfaceAllowed(pile), name + " 应属地表放行的五种矿");
            assertEquals(
                    OrePileRules.Outcome.ORIGINAL,
                    OrePileRules.decide(true, pile, gate(false, false)),
                    name + " 在「保留原矿」分支里应保持原样");
        }
    }

    // ---- 非五种矿在地表变绿宝石 ----

    @Test
    void nonPremiumSurfaceOresBecomeEmerald() {
        for (Block ore : List.of(Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE)) {
            OreConfiguration pile = pile(ore);
            String name = idOf(ore);
            assertFalse(OrePileRules.isSurfaceAllowed(pile), name + " 不应属地表放行的五种矿");
            assertEquals(
                    OrePileRules.Outcome.EMERALD,
                    OrePileRules.decide(true, pile, gate(false, false)),
                    name + " 在「保留原矿」分支里应换绿宝石");
            OreConfiguration converted = OrePileRules.toEmerald(pile);
            assertEquals(Blocks.EMERALD_ORE.defaultBlockState(), converted.targetStates.get(0).state, name);
            assertEquals(pile.size, converted.size, name + " 的 size 应原样保留");
            assertEquals(
                    pile.discardChanceOnAirExposure,
                    converted.discardChanceOnAirExposure,
                    0.0F,
                    name + " 的 discardChanceOnAirExposure 应原样保留");
        }
    }

    // ---- 深板岩变体 ----

    @Test
    void deepslateTargetsSelectTheDeepslateEmeraldVariant() {
        assertEquals(
                Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState(),
                OrePileRules.emeraldFor(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()),
                "deepslate_ 前缀应选深板岩变体");
        assertEquals(
                Blocks.EMERALD_ORE.defaultBlockState(),
                OrePileRules.emeraldFor(Blocks.LAPIS_ORE.defaultBlockState()),
                "普通地层应选普通绿宝石矿");
        assertEquals(
                Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState(),
                OrePileRules.emeraldFor(groundProbeOre.defaultBlockState()),
                "c:ores_in_ground/deepslate tag 应选深板岩变体");
    }

    @Test
    void emeraldConversionPicksTheVariantPerTarget() {
        OreConfiguration mixed = new OreConfiguration(
                List.of(
                        OreConfiguration.target(new BlockMatchTest(Blocks.STONE), Blocks.DIAMOND_ORE.defaultBlockState()),
                        OreConfiguration.target(
                                new BlockMatchTest(Blocks.DEEPSLATE), Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState())),
                7,
                0.25F);
        OreConfiguration converted = OrePileRules.toEmerald(mixed);
        assertEquals(Blocks.EMERALD_ORE.defaultBlockState(), converted.targetStates.get(0).state);
        assertEquals(Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState(), converted.targetStates.get(1).state);
        assertEquals(7, converted.size);
        assertEquals(0.25F, converted.discardChanceOnAirExposure, 0.0F);
    }

    // ---- 绿宝石分支只在地表 lane ----

    @Test
    void otherLanesKeepTheOldSplitForNonPremiumOres() {
        OreConfiguration other = pile(Blocks.LAPIS_ORE);
        int cancel = 0;
        int original = 0;
        int andesite = 0;
        for (int seed = 0; seed < ROLL_SAMPLES; seed++) {
            switch (OrePileRules.decide(false, other, gateFor(seed))) {
                case CANCEL -> cancel++;
                case ORIGINAL -> original++;
                case ANDESITE -> andesite++;
                case EMERALD -> fail("绿宝石分支只应在地表 lane 生效");
            }
        }
        assertShare(0.50, cancel, "其它 lane 整堆取消");
        assertShare(0.25, original, "其它 lane 保留原矿");
        assertShare(0.25, andesite, "其它 lane 换安山岩");
    }

    @Test
    void otherLanesIgnoreTheSurfaceWhitelist() {
        OreConfiguration premium = pile(Blocks.COAL_ORE);
        OreConfiguration other = pile(Blocks.DIAMOND_ORE);
        for (int seed = 0; seed < 500; seed++) {
            assertEquals(
                    OrePileRules.decide(false, premium, gateFor(seed)),
                    OrePileRules.decide(false, other, gateFor(seed)),
                    "非地表 lane 的抽签结果不应受白名单影响，seed=" + seed);
        }
    }

    // ---- 50 / 25 / 25 三分支各自可达 ----

    @Test
    void premiumSurfaceOresKeepTheFiftyTwentyFiveTwentyFiveSplit() {
        OreConfiguration premium = pile(Blocks.COAL_ORE);
        int cancel = 0;
        int original = 0;
        int andesite = 0;
        for (int seed = 0; seed < ROLL_SAMPLES; seed++) {
            switch (OrePileRules.decide(true, premium, gateFor(seed))) {
                case CANCEL -> cancel++;
                case ORIGINAL -> original++;
                case ANDESITE -> andesite++;
                case EMERALD -> fail("煤是五种之一，不应走绿宝石分支");
            }
        }
        assertShare(0.50, cancel, "整堆取消");
        assertShare(0.25, original, "保留原矿");
        assertShare(0.25, andesite, "换安山岩");
    }

    @Test
    void nonPremiumSurfaceOresKeepTheSplitWithOriginalTradedForEmerald() {
        OreConfiguration other = pile(Blocks.LAPIS_ORE);
        int cancel = 0;
        int emerald = 0;
        int andesite = 0;
        for (int seed = 0; seed < ROLL_SAMPLES; seed++) {
            switch (OrePileRules.decide(true, other, gateFor(seed))) {
                case CANCEL -> cancel++;
                case EMERALD -> emerald++;
                case ANDESITE -> andesite++;
                case ORIGINAL -> fail("地表非五种矿不应原样生成");
            }
        }
        assertShare(0.50, cancel, "地表非五种矿整堆取消");
        assertShare(0.25, emerald, "地表非五种矿换绿宝石");
        assertShare(0.25, andesite, "地表非五种矿换安山岩");
    }

    // ---- helpers ----

    private static OreConfiguration pile(Block ore) {
        return new OreConfiguration(new BlockMatchTest(Blocks.STONE), ore.defaultBlockState(), 4, 0.35F);
    }

    private static String idOf(Block block) {
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(block));
    }

    private static Block registerProbe(String path) {
        return Registry.register(
                BuiltInRegistries.BLOCK,
                ResourceLocation.fromNamespaceAndPath("orepiletests", path),
                new Block(BlockBehaviour.Properties.of()));
    }

    private static List<Holder<Block>> holders(Block... blocks) {
        List<Holder<Block>> holders = new ArrayList<>(blocks.length);
        for (Block block : blocks) {
            holders.add(BuiltInRegistries.BLOCK.wrapAsHolder(block));
        }
        return holders;
    }

    /**
     * 每个样本用 splitmix64 打散后的种子。直接拿 0,1,2… 当种子不行：LegacyRandomSource 是 LCG，
     * 相邻种子的第一次 {@code nextBoolean()} 高度相关（实测 0..1999 全为同一值）。
     */
    private static RandomSource gateFor(int sample) {
        long mixed = sample + 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return RandomSource.create(mixed);
    }

    /** 找到一个让两次门控掷骰等于给定结果的种子（cancel = 第一掷为 true，andesite = 第二掷为 true）。 */
    private static RandomSource gate(boolean cancel, boolean andesite) {
        for (long seed = 0; seed < 100_000L; seed++) {
            RandomSource candidate = RandomSource.create(seed);
            if (candidate.nextBoolean() != cancel) {
                continue;
            }
            if (candidate.nextBoolean() != andesite) {
                continue;
            }
            return RandomSource.create(seed);
        }
        throw new AssertionError("找不到 cancel=" + cancel + ", andesite=" + andesite + " 的种子");
    }

    private static void assertShare(double expected, int count, String label) {
        double share = count / (double) ROLL_SAMPLES;
        assertTrue(
                Math.abs(share - expected) < 0.03,
                label + " 占比 " + share + " 应接近 " + expected + "（" + count + "/" + ROLL_SAMPLES + "）");
    }
}
