package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import org.junit.jupiter.api.Test;

/**
 * T-14 纯逻辑单测：收购/出售判定、1–5 级跨级候选池、空池回退。
 *
 * <p>为什么不用真实 {@code MerchantOffer}：纯 JUnit JVM 里原版注册表不可用——实测
 * {@code new MerchantOffer(...)} 在 {@code ItemStack.<clinit>} 处抛 {@code ExceptionInInitializerError}，
 * 根因是 {@code BuiltInRegistries.<clinit>} 要求先 Bootstrap（"Not bootstrapped"），
 * 而 Bootstrap 又卡在 NeoForge 的 FeatureFlags 加载。所以判定输入用 {@link VillagerTradeSwap.OfferShape}
 * （两个物品 id），候选表用本文件自己的假 listing 与 probe 造。
 * {@code MerchantOffer} 适配层（{@code getBaseCostA()}/{@code getResult()}/{@code copy()}）只能实机验证。</p>
 */
class VillagerTradeSwapTest {

    private static final String EMERALD = "minecraft:emerald";
    private static final String WHEAT = "minecraft:wheat";

    // ---- 判定：收购 = 产物绿宝石且成本不是；出售 = 成本绿宝石且产物不是 ----

    @Test
    void buyingIsItemForEmerald() {
        VillagerTradeSwap.OfferShape wheatForEmerald = shape(WHEAT, EMERALD);
        assertTrue(wheatForEmerald.isBuy());
        assertFalse(wheatForEmerald.isSell());
    }

    @Test
    void sellingIsEmeraldForItem() {
        VillagerTradeSwap.OfferShape emeraldForBread = shape(EMERALD, "minecraft:bread");
        assertTrue(emeraldForBread.isSell());
        assertFalse(emeraldForBread.isBuy());
    }

    @Test
    void emeraldForEmeraldIsNeither() {
        VillagerTradeSwap.OfferShape emeraldForEmerald = shape(EMERALD, EMERALD);
        assertFalse(emeraldForEmerald.isBuy());
        assertFalse(emeraldForEmerald.isSell(), "绿宝石换绿宝石两边都成立，按约定不碰");
    }

    @Test
    void itemForItemIsNeither() {
        VillagerTradeSwap.OfferShape leavesForMoss = shape("minecraft:oak_leaves", "minecraft:moss_block");
        assertFalse(leavesForMoss.isBuy(), "模组职业的物品换物品既不是收购也不是出售");
        assertFalse(leavesForMoss.isSell());
    }

    // ---- 候选池：整职业 1–5 级并集，本级没有出售条目也要能跨级取到 ----

    @Test
    void onlySellListingsEnterThePool() {
        VillagerTrades.ItemListing buy = marker();
        VillagerTrades.ItemListing sell = marker();
        VillagerTrades.ItemListing neither = marker();
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel =
                table(1, new VillagerTrades.ItemListing[] {buy, sell, neither});
        Map<VillagerTrades.ItemListing, VillagerTradeSwap.OfferShape> shapes = new IdentityHashMap<>();
        shapes.put(buy, shape(WHEAT, EMERALD));
        shapes.put(sell, shape(EMERALD, "minecraft:bread"));
        shapes.put(neither, shape("minecraft:oak_leaves", "minecraft:moss_block"));

        List<VillagerTradeSwap.Candidate> pool = VillagerTradeSwap.collectSellCandidates(byLevel, probe(shapes));

        assertEquals(List.of(new VillagerTradeSwap.Candidate(1, 1)), pool);
    }

    @Test
    void aLevelWithNoSellOfferStillGetsOneFromALowerLevel() {
        // 渔夫形状：4 级只有「热带鱼→绿宝石」，本级没有出售条目；1 级有「绿宝石→鳕鱼桶」。
        VillagerTrades.ItemListing level4Buy = marker();
        VillagerTrades.ItemListing level1Sell = marker();
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel = table(
                1, new VillagerTrades.ItemListing[] {level1Sell},
                4, new VillagerTrades.ItemListing[] {level4Buy});
        Map<VillagerTrades.ItemListing, VillagerTradeSwap.OfferShape> shapes = new IdentityHashMap<>();
        shapes.put(level4Buy, shape("minecraft:tropical_fish", EMERALD));
        shapes.put(level1Sell, shape(EMERALD, "minecraft:cod_bucket"));

        List<VillagerTradeSwap.Candidate> pool = VillagerTradeSwap.collectSellCandidates(byLevel, probe(shapes));

        assertEquals(List.of(new VillagerTradeSwap.Candidate(1, 0)), pool);
        assertEquals(0, VillagerTradeSwap.pickCandidate(pool.size(), RandomSource.create(11L)),
                "池只有这一条时替换一定取到它（本级那条收购条目不参与竞争）");
    }

    @Test
    void aLevelWithNoSellOfferStillGetsOneFromAHigherLevel() {
        // 武器匠形状：3 级只有「燧石→绿宝石」；5 级有「绿宝石→附魔钻石剑」。
        VillagerTrades.ItemListing level3Buy = marker();
        VillagerTrades.ItemListing level5Sell = marker();
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel = table(
                3, new VillagerTrades.ItemListing[] {level3Buy},
                5, new VillagerTrades.ItemListing[] {level5Sell});
        Map<VillagerTrades.ItemListing, VillagerTradeSwap.OfferShape> shapes = new IdentityHashMap<>();
        shapes.put(level3Buy, shape("minecraft:flint", EMERALD));
        shapes.put(level5Sell, shape(EMERALD, "minecraft:diamond_sword"));

        List<VillagerTradeSwap.Candidate> pool = VillagerTradeSwap.collectSellCandidates(byLevel, probe(shapes));

        assertEquals(List.of(new VillagerTradeSwap.Candidate(5, 0)), pool);
    }

    @Test
    void listingsThatCannotBuildAnOfferAreSkipped() {
        VillagerTrades.ItemListing failing = marker();
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel =
                table(2, new VillagerTrades.ItemListing[] {failing});
        // probe 返回 null：FailureItemListing / 未知村民类型取不到产物
        List<VillagerTradeSwap.Candidate> pool = VillagerTradeSwap.collectSellCandidates(byLevel, listing -> null);

        assertTrue(pool.isEmpty());
    }

    @Test
    void levelsOutsideOneToFiveAreNotCandidates() {
        VillagerTrades.ItemListing level6Sell = marker();
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel =
                table(6, new VillagerTrades.ItemListing[] {level6Sell});
        Map<VillagerTrades.ItemListing, VillagerTradeSwap.OfferShape> shapes = new IdentityHashMap<>();
        shapes.put(level6Sell, shape(EMERALD, "minecraft:bread"));

        assertTrue(VillagerTradeSwap.collectSellCandidates(byLevel, probe(shapes)).isEmpty(),
                "原版等级上限 5；6 级只可能是模组越界数据，不参与替换");
    }

    // ---- 挑选与回退 ----

    @Test
    void emptyPoolMeansFallbackToTheOriginalBuyOffer() {
        assertEquals(-1, VillagerTradeSwap.pickCandidate(0, RandomSource.create(7L)));
        assertEquals(-1, VillagerTradeSwap.pickCandidate(-3, RandomSource.create(7L)));
    }

    @Test
    void pickStaysInsideThePoolAndReachesEveryCandidate() {
        RandomSource random = RandomSource.create(42L);
        Set<Integer> seen = new HashSet<>();
        for (int draw = 0; draw < 200; draw++) {
            int pick = VillagerTradeSwap.pickCandidate(3, random);
            assertTrue(pick >= 0 && pick < 3, "pick " + pick);
            seen.add(pick);
        }
        assertEquals(3, seen.size(), "种子固定，200 抽覆盖三个候选是确定结果");
    }

    @Test
    void nonVillagerTradersAreRejectedBeforeAnyTableWork() {
        // 流浪商人是 AbstractVillager 的另一支，不是 Villager：入口必须在碰职业表/交易列表前返回。
        // 传 null（非 Villager）且后两个参数为 null——少了 instanceof 守卫就会 NPE。
        assertDoesNotThrow(() -> VillagerTradeSwap.replaceEmeraldBuys(null, null, null));
    }

    // ---- 测试脚手架 ----

    private static VillagerTradeSwap.OfferShape shape(String costAItemId, String resultItemId) {
        return new VillagerTradeSwap.OfferShape(costAItemId, resultItemId);
    }

    /**
     * 只用来占位的假条目：形状由 probe 表另给，{@code getOffer} 绝不应被调用到。
     * 必须每次新建实例——非捕获 lambda 会被 JVM 按调用点缓存，三次 {@code marker()} 会得到同一个对象，
     * 身份表就会互相覆盖（首轮实现后本测试正是这样红的）。
     */
    private static VillagerTrades.ItemListing marker() {
        return new VillagerTrades.ItemListing() {
            @Override
            public MerchantOffer getOffer(Entity trader, RandomSource random) {
                return null;
            }
        };
    }

    private static VillagerTradeSwap.ShapeProbe probe(Map<VillagerTrades.ItemListing, VillagerTradeSwap.OfferShape> shapes) {
        return shapes::get;
    }

    private static Int2ObjectMap<VillagerTrades.ItemListing[]> table(Object... levelAndListings) {
        Int2ObjectMap<VillagerTrades.ItemListing[]> map = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < levelAndListings.length; i += 2) {
            map.put(((Integer) levelAndListings[i]).intValue(), (VillagerTrades.ItemListing[]) levelAndListings[i + 1]);
        }
        return map;
    }
}
