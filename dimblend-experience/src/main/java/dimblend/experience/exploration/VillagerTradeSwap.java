package dimblend.experience.exploration;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * F 村民只卖不收：把 G1 全量重建出来的每一条「收购条目」随机替换成本职业的一条「出售条目」。
 *
 * <p><b>判定</b>以绿宝石为记账单位，读底价 {@code getBaseCostA()}（{@code getCostA()} 叠了
 * demand/折扣，物品类型虽同，语义上应读底价）：玩家拿到的是绿宝石、付的不是 → 收购；
 * 玩家付的是绿宝石、拿到的不是 → 出售；两边都成立（绿宝石→绿宝石）或都不成立
 * （物品→物品，如模组职业的树叶换苔藓）→ 原样不动。costB（书/箭/生鱼/指南针）不参与判定，
 * 玩家总要付 costA。</p>
 *
 * <p><b>候选池</b>取该职业 1–5 级<b>全部</b>出售条目的并集，不能只取「本次重建用的那一级」：
 * 原版渔夫 4/5 级、武器匠 3 级、屠夫 3/4/5 级在 {@code VillagerTrades.TRADES} 里只有收购条目，
 * 按级取池会让这些级一条都换不掉。池空（该职业没有任何出售条目）时保留原收购条目——
 * 宁可少换一条，也不静默丢条目。</p>
 *
 * <p><b>调用点</b>是 {@code VillagerMasterRules#rebuildTrades} 逐级重建之后、{@code clampSingleUse}
 * 之前：替换出来的出售条目保留它自己的 {@code maxUses}/xp，随后被既有规则钳成一次。
 * 每个被替换的槽位放独立 {@code copy()}——{@code uses}/{@code maxUses}/折扣都是 per-offer 状态，
 * 同一实例进两个槽会让一条成交标记两个槽。</p>
 *
 * <p><b>代价与前提</b>：每级每职业枚举一遍 listing 会实例化全部条目（含附魔条目掷骰），
 * 一个被收编村民一次，只在真的出现收购条目时才枚举。前提是 A13 已把
 * {@code TreasureMapForEmeralds} 全局置 null，否则枚举会触发结构搜索。只对 {@link Villager}
 * 生效——流浪商人同属 {@code AbstractVillager}，没有职业交易表。存量村民不追溯（与 G1 口径一致）。</p>
 *
 * <p>纯单测 JVM 里原版注册表不可用（{@code BuiltInRegistries} 要求先 Bootstrap，实测
 * "Not bootstrapped"），所以判定与选池以 {@link OfferShape}（两个物品 id）为输入，
 * {@code MerchantOffer} 只出现在最薄的一层适配里。</p>
 */
public final class VillagerTradeSwap {

    /** 原版村民等级 1–5（{@code VillagerData#canLevelUp} 的上界）；候选池按整职业跨这五级取并集。 */
    static final int MAX_LEVEL = 5;

    /** 记账货币的物品 id。判定按 id 比而不是 {@code ItemStack#is}，规则才能在纯单测里覆盖。 */
    static final String EMERALD_ID = "minecraft:emerald";

    /** 一条交易的方向判定只需两件事：玩家第一件付什么（底价 costA）、拿到什么（产物）。 */
    record OfferShape(String costAItemId, String resultItemId) {

        boolean paysEmerald() {
            return EMERALD_ID.equals(costAItemId);
        }

        boolean givesEmerald() {
            return EMERALD_ID.equals(resultItemId);
        }

        /** 收购：产物是绿宝石、成本不是。 */
        boolean isBuy() {
            return givesEmerald() && !paysEmerald();
        }

        /** 出售：成本是绿宝石、产物不是。绿宝石→绿宝石两边都成立，按约定既非收购也非出售。 */
        boolean isSell() {
            return paysEmerald() && !givesEmerald();
        }
    }

    /** 候选在职业交易表里的位置：等级 + 该级数组下标。只存位置不存 offer，避免槽位之间共享 stack。 */
    record Candidate(int level, int index) {
    }

    /** 把一条 listing 实例化成 offer 后读出的形状；拿不到 offer（{@code FailureItemListing} 等）返回 null。 */
    @FunctionalInterface
    interface ShapeProbe {
        @Nullable
        OfferShape shapeOf(VillagerTrades.ItemListing listing);
    }

    /** 真实 offer 的形状（薄适配层：原版交易类在纯 JUnit 里不可用，规则本身不依赖它们）。 */
    static OfferShape shapeOf(MerchantOffer offer) {
        return new OfferShape(itemId(offer.getBaseCostA()), itemId(offer.getResult()));
    }

    /** 收购判定：产物绿宝石、成本不是绿宝石。 */
    public static boolean isBuyOffer(MerchantOffer offer) {
        return shapeOf(offer).isBuy();
    }

    /** 出售判定：成本绿宝石、产物不是绿宝石。 */
    public static boolean isSellOffer(MerchantOffer offer) {
        return shapeOf(offer).isSell();
    }

    /** 该职业 1–5 级全部能产出出售条目的位置（跨级并集，等级升序）；池空返回空表。 */
    static List<Candidate> collectSellCandidates(Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel, ShapeProbe probe) {
        List<Candidate> pool = new ArrayList<>();
        for (int level = 1; level <= MAX_LEVEL; level++) {
            VillagerTrades.ItemListing[] listings = byLevel.get(level);
            if (listings == null) {
                continue;
            }
            for (int index = 0; index < listings.length; index++) {
                OfferShape shape = probe.shapeOf(listings[index]);
                if (shape != null && shape.isSell()) {
                    pool.add(new Candidate(level, index));
                }
            }
        }
        return pool;
    }

    /** 纯挑选：空池返回 -1，调用方据此保留原收购条目。 */
    static int pickCandidate(int poolSize, RandomSource random) {
        return poolSize <= 0 ? -1 : random.nextInt(poolSize);
    }

    /**
     * 入口：把 {@code offers} 里每一条收购条目换成本职业随机一条出售条目。
     * 在 {@code VillagerMasterRules#rebuildTrades} 重建之后、{@code clampSingleUse} 之前调用。
     */
    public static void replaceEmeraldBuys(Entity trader, VillagerProfession profession, MerchantOffers offers) {
        if (!(trader instanceof Villager villager)) {
            return; // 流浪商人同属 AbstractVillager，但没有职业交易表，一律不碰
        }
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel = VillagerTrades.TRADES.get(profession);
        if (byLevel == null) {
            return;
        }
        RandomSource random = villager.getRandom();
        List<Candidate> pool = null;
        for (int slot = 0; slot < offers.size(); slot++) {
            if (!isBuyOffer(offers.get(slot))) {
                continue;
            }
            if (pool == null) {
                // 只在真的出现收购条目时才枚举整张表（无收购的职业/重建零额外开销）
                pool = collectSellCandidates(byLevel, listing -> {
                    MerchantOffer offer = listing.getOffer(villager, random);
                    return offer == null ? null : shapeOf(offer);
                });
            }
            int pick = pickCandidate(pool.size(), random);
            if (pick < 0) {
                continue; // 该职业 1–5 级没有任何出售条目：保留原收购条目（回退）
            }
            MerchantOffer replacement = offerOf(pool.get(pick), byLevel, villager, random);
            if (replacement == null) {
                continue;
            }
            // 独立副本：uses/maxUses/折扣是 per-offer 状态，同一实例进两槽会互相标记
            offers.set(slot, replacement.copy());
        }
    }

    /** 按候选位置重新实例化一条 offer；表在两次读取之间被服务端数据重载时返回 null。 */
    private static @Nullable MerchantOffer offerOf(
            Candidate candidate, Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel, Villager villager, RandomSource random) {
        VillagerTrades.ItemListing[] listings = byLevel.get(candidate.level());
        if (listings == null || candidate.index() >= listings.length) {
            return null;
        }
        return listings[candidate.index()].getOffer(villager, random);
    }

    /** 物品注册 id；未注册（理论上不可达）返回空串，按「不是绿宝石」处理而不抛异常。 */
    private static String itemId(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private VillagerTradeSwap() {
    }
}
