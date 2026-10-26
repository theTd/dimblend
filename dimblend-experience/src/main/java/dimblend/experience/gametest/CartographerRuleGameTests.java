package dimblend.experience.gametest;

import com.mojang.datafixers.util.Pair;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.exploration.ExplorationAttachments;
import dimblend.experience.exploration.VillagerMasterRules;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A13 运行时检查（两条规则都是原版行为链上的拦截，纯函数单测覆盖不到）：
 *
 * <ol>
 * <li>探险图交易：{@code TreasureMapForEmeralds.getOffer} 一律返回 null 且不进入
 * 结构搜索——用例故意传 {@code null} trader 调用：原版方法首行就要
 * {@code trader.level()}，注入若不在 HEAD 短路会直接 NPE，从而证明搜索未被调用。</li>
 * <li>制图师工作站：{@code #minecraft:acquirable_job_site} 不再含
 * {@code minecraft:cartographer}；无业村民挨着制图台不转职；旧存档里已记下的
 * {@code POTENTIAL_JOB_SITE} 会在原版 {@code ValidateNearbyPoi} 里被清掉，
 * 不会在走到桌前被 {@code AssignProfessionFromJobSite} 认领。</li>
 * <li>G1 收编制图师：一次扫描内完成（&lt; 500 ms），等级 5、无探险图条目、
 * 全部 maxUses=1。</li>
 * </ol>
 *
 * <p>改全局配置的用例各自独占一个 batch（与 MonsterDensityGameTests 同一约定），
 * 配置在 finally 还原。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class CartographerRuleGameTests {

    private static final String TEMPLATE = "density_chamber";
    private static final String NAMESPACE = "dimblend_experience";
    /** 7x5x7 all-air template: a 5x5 stone floor at y=1 keeps villagers from falling. */
    private static final BlockPos VILLAGER = new BlockPos(3, 2, 3);
    private static final BlockPos TABLE = new BlockPos(3, 2, 4);

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 60,
            batch = "cartographer_map_listing")
    public static void mapListingYieldsNoOfferAndNoSearch(GameTestHelper helper) {
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel =
                VillagerTrades.TRADES.get(VillagerProfession.CARTOGRAPHER);
        helper.assertTrue(byLevel != null, "cartographer trade table missing");
        VillagerTrades.ItemListing[] level2 = byLevel.get(2);
        helper.assertTrue(level2 != null && level2.length >= 2,
                "cartographer level 2 must keep its vanilla listings, got " + (level2 == null ? "null" : level2.length));

        int nullOffers = 0;
        for (VillagerTrades.ItemListing listing : level2) {
            // Null trader on purpose: the vanilla map listing dereferences it before the
            // structure search, so a non-HEAD short-circuit fails this call with an NPE.
            MerchantOffer offer = listing.getOffer(null, RandomSource.create(7L));
            if (offer == null) {
                nullOffers++;
            } else {
                helper.assertFalse(offer.getResult().is(Items.FILLED_MAP),
                        "no explorer map may be offered, got " + offer.getResult());
            }
        }
        helper.assertTrue(nullOffers >= 1, "the treasure-map listing must yield no offer");
        helper.succeed();
    }

    /** The normal vanilla level-up path ({@code AbstractVillager.getOffers -> updateTrades}). */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 60,
            batch = "cartographer_map_villager")
    public static void cartographerVillagerOffersContainNoExplorerMap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildFloor(helper);
        Villager villager = spawnVillager(helper, VILLAGER);
        villager.setVillagerData(villager.getVillagerData()
                .setProfession(VillagerProfession.CARTOGRAPHER).setLevel(2));
        MerchantOffers offers = villager.getOffers();
        helper.assertFalse(offers.isEmpty(),
                "level 2 cartographer must keep its non-map trades, got none");
        assertNoExplorerMaps(helper, offers, "level-up");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 60,
            batch = "cartographer_jobsite_tag")
    public static void cartographerPoiIsNotAcquirableJobSite(GameTestHelper helper) {
        Holder.Reference<PoiType> cartographer =
                BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(PoiTypes.CARTOGRAPHER);
        helper.assertFalse(cartographer.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
                "minecraft:cartographer must not be in #minecraft:acquirable_job_site");
        helper.assertFalse(VillagerProfession.ALL_ACQUIRABLE_JOBS.test(cartographer),
                "VillagerProfession.ALL_ACQUIRABLE_JOBS must reject the cartography table");
        Holder.Reference<PoiType> librarian =
                BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(PoiTypes.LIBRARIAN);
        helper.assertTrue(librarian.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
                "other workstations (librarian) must stay acquirable job sites");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 120,
            batch = "cartographer_jobsite_table")
    public static void unemployedVillagerNextToCartographyTableStaysUnemployed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildFloor(helper);
        helper.setBlock(TABLE, Blocks.CARTOGRAPHY_TABLE);
        Villager villager = spawnVillager(helper, VILLAGER);

        // Deterministic core of the rule: the exact predicate AcquirePoi scans with
        // (Villager.POI_MEMORIES -> POTENTIAL_JOB_SITE) must not match the placed table.
        BlockPos tablePos = helper.absolutePos(TABLE);
        boolean tableClaimable = level.getPoiManager()
                .findAllClosestFirstWithType(holder -> holder.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
                        pos -> true, tablePos, 48, PoiManager.Occupancy.HAS_SPACE)
                .map(Pair::getSecond)
                .anyMatch(tablePos::equals);
        helper.assertFalse(tableClaimable, "the cartography table must not be an acquirable job site");

        helper.runAfterDelay(60L, () -> {
            helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE,
                    "villager next to a cartography table must stay unemployed, got "
                            + villager.getVillagerData().getProfession());
            helper.succeed();
        });
    }

    /** Old saves: a POTENTIAL_JOB_SITE memory on a cartography table must not become a profession. */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 120,
            batch = "cartographer_jobsite_stale")
    public static void stalePotentialJobSiteAtCartographyTableIsNotClaimed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildFloor(helper);
        helper.setBlock(TABLE, Blocks.CARTOGRAPHY_TABLE);
        Villager villager = spawnVillager(helper, VILLAGER);
        villager.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(TABLE)));

        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE,
                    "a remembered cartography table must not grant the profession, got "
                            + villager.getVillagerData().getProfession());
            helper.assertFalse(villager.getBrain().hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE),
                    "the stale potential job site must be erased instead of claimed");
            helper.succeed();
        });
    }

    /** G1: adopting a stamped cartographer is one in-tick rebuild with no explorer maps. */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 120,
            batch = "villager_master_cartographer")
    public static void masterAdoptionOfCartographerCompletesInOneScan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String oldDimension = Config.ROTATING_DIMENSION_ID.get();
        boolean oldMaster = Config.VILLAGER_MASTER.get();
        try {
            Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
            Config.VILLAGER_MASTER.set(true);
            buildFloor(helper);
            Villager villager = spawnVillager(helper, VILLAGER);
            villager.setVillagerData(villager.getVillagerData()
                    .setProfession(VillagerProfession.CARTOGRAPHER).setLevel(1));
            villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), true);

            long started = System.nanoTime();
            VillagerMasterRules.scanLevel(level);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            helper.assertTrue(elapsedMs < 500L,
                    "adoption must stay inside one tick (no structure search), took " + elapsedMs + " ms");
            helper.assertTrue(villager.getVillagerData().getLevel() == 5,
                    "adopted villager must be level 5, got " + villager.getVillagerData().getLevel());
            helper.assertTrue("minecraft:cartographer"
                            .equals(villager.getData(ExplorationAttachments.VILLAGER_PROFESSION.get())),
                    "adoption must record the profession attachment");
            MerchantOffers offers = villager.getOffers();
            DimBlend.LOGGER.info("[A13] cartographer adoption scan: {} ms, {} offers, no explorer map",
                    elapsedMs, offers.size());
            helper.assertFalse(offers.isEmpty(), "adopted cartographer must have the rebuilt trades");
            assertNoExplorerMaps(helper, offers, "adoption");
            for (MerchantOffer offer : offers) {
                helper.assertTrue(offer.getMaxUses() == 1, "every adopted trade must be single use");
            }
        } finally {
            Config.ROTATING_DIMENSION_ID.set(oldDimension);
            Config.VILLAGER_MASTER.set(oldMaster);
        }
        helper.succeed();
    }

    private static void assertNoExplorerMaps(GameTestHelper helper, MerchantOffers offers, String path) {
        for (MerchantOffer offer : offers) {
            helper.assertFalse(offer.getResult().is(Items.FILLED_MAP),
                    path + " must not offer an explorer map, got " + offer.getResult());
            helper.assertFalse(offer.getResult().getItem() instanceof net.minecraft.world.item.MapItem,
                    path + " must not offer any map item, got " + offer.getResult());
        }
    }

    private static Villager spawnVillager(GameTestHelper helper, BlockPos relative) {
        Villager villager = EntityType.VILLAGER.spawn(helper.getLevel(), helper.absolutePos(relative),
                MobSpawnType.COMMAND);
        helper.assertTrue(villager != null, "villager must spawn at " + relative);
        return villager;
    }

    private static void buildFloor(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
            }
        }
    }

    private CartographerRuleGameTests() {
    }
}
