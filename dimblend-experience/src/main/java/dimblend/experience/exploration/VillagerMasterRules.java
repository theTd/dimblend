package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.mixin.MerchantOfferAccessor;
import dimblend.experience.mixin.VillagerAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * G1 村民大师收编（服务端定期扫描认戳）：
 * 戳有两个来源——转职瞬间由 {@code VillagerMasterMixin} 打上（工作站点认领），
 * 新生瞬间由下方 {@code onFinalizeSpawn} 打上（刷怪蛋 / /summon / 繁殖 / 治愈 /
 * 自然 / 结构；读档加载不走此事件，存量村民天然无戳，满足“仅新生成”）。
 * rotating 内定期扫村民——有戳即收编：无业新生先随机指派职业（不含无业/傻子），
 * 再瞬间定大师（level 5）、补全该职业 1–5 级全部交易（每级照原版取
 * 2 条）、全部条目 {@code maxUses=1}、职业 id 记附件、戳消费。掉职业的已收编
 * 村民凭附件恢复职业（工作站点丢失主由 mixin 拦截，此处为双保险）。
 *
 * <p>不追溯原则：只认戳——老存档村民天然无戳，永不收编；开关关闭期转职/生成不打戳，
 * 同样不收。戳持久化，重启不丢；转职后何时交易都不影响收编（无窗口竞态）。
 * 只看戳，不看交易/经验（`getOffers()` 自带物化副作用，不能拿它判新鲜）。</p>
 *
 * <p>重建只发生在新收编或转新职业时——他人半成品不会被覆盖。已收编且满级、
 * 交易齐备的走廉价路径（只 enforcement maxUses）。升级经验照常累计，
 * 5 级封顶不再升级故不添新条目；补货由 mixin 整体取消。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class VillagerMasterRules {

    private static final int SCAN_INTERVAL = 40;
    private static final int MASTER_LEVEL = 5;
    private static final int SINGLE_USE = 1;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level raw = event.getLevel();
        if (!(raw instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.VILLAGER_MASTER.get()) {
            return;
        }
        if (level.getGameTime() % SCAN_INTERVAL != 0) {
            return;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager villager && entity.getType() == EntityType.VILLAGER) {
                normalize(villager);
            }
        }
    }

    /**
     * 新生打戳：rotating 服务端内生成的村民统一带戳（转职前）。
     * 读档加载（NBT 直写 entityData）不走此事件，故存量村民天然无戳；
     * 出生自带职业的新生凭此戳直接收编；无业新生凭此戳随机指派职业；傻子由扫描跳过。
     */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.isSpawnCancelled()) {
            return;
        }
        if (!Config.VILLAGER_MASTER.get()) {
            return;
        }
        ServerLevel level = resolveServerLevel(event.getLevel());
        if (level == null || !RotatingDimension.is(level)) {
            return;
        }
        if (event.getEntity() instanceof Villager villager && event.getEntity().getType() == EntityType.VILLAGER) {
            if (villager.getData(ExplorationAttachments.VILLAGER_PROFESSION.get()).isEmpty()) {
                villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), true);
            }
        }
    }

    /**
     * worldgen 阶段（区块生成/结构体）事件的 level 载体是 WorldGenRegion，
     * 需解包出真正的 ServerLevel 才能判维度；未知载体返回 null 跳过。
     */
    private static ServerLevel resolveServerLevel(LevelAccessor level) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        if (level instanceof WorldGenRegion region) {
            return region.getLevel();
        }
        return null;
    }

    private static void normalize(Villager villager) {
        if (villager.isBaby()) {
            return;
        }
        VillagerProfession profession = villager.getVillagerData().getProfession();
        String recorded = villager.getData(ExplorationAttachments.VILLAGER_PROFESSION.get());
        boolean fresh = villager.getData(ExplorationAttachments.VILLAGER_FRESH.get());
        if (profession == VillagerProfession.NITWIT) {
            // 傻子永不收编：消费残留戳直接返回；已收编者被改成傻子视为掉职业，照常恢复
            villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), false);
            if (recorded.isEmpty()) {
                return;
            }
            VillagerProfession want = BuiltInRegistries.VILLAGER_PROFESSION
                    .get(ResourceLocation.tryParse(recorded));
            if (want == null) {
                // 记录的职业 id 已失效（如移除 Mod 职业）：清记录降级为存量，避免每轮空转
                villager.setData(ExplorationAttachments.VILLAGER_PROFESSION.get(), "");
                return;
            }
            villager.setVillagerData(villager.getVillagerData().setProfession(want));
            profession = want;
        } else if (profession == VillagerProfession.NONE) {
            if (recorded.isEmpty()) {
                // 无业：有戳是新生（spawn 戳）——随机指派职业并收编；无戳是存量，不追溯
                if (fresh) {
                    assignRandomProfessionAndAdopt(villager);
                }
                return;
            }
            // 已收编掉职业：恢复（工作站点丢失主由 mixin 拦截，此处为双保险）
            villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), false);
            VillagerProfession want = BuiltInRegistries.VILLAGER_PROFESSION
                    .get(ResourceLocation.tryParse(recorded));
            if (want == null) {
                // 记录的职业 id 已失效：清记录降级为存量，避免每轮空转
                villager.setData(ExplorationAttachments.VILLAGER_PROFESSION.get(), "");
                return;
            }
            villager.setVillagerData(villager.getVillagerData().setProfession(want));
            profession = want;
        }
        String professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).toString();
        if (recorded.isEmpty()) {
            // 未收编：只认戳——老存档村民无戳，直接跳过，不追溯
            if (!villager.getData(ExplorationAttachments.VILLAGER_FRESH.get())) {
                return;
            }
            villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), false);
            adopt(villager, profession, professionId);
        } else if (!recorded.equals(professionId)) {
            // 已收编但换了职业：按新职业重建（转职自带新戳，此处顺手消费）
            villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), false);
            adopt(villager, profession, professionId);
        } else {
            // 已收编：廉价路径——满级不够补，交易空了补，戳顺手消费
            villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), false);
            if (villager.getVillagerData().getLevel() < MASTER_LEVEL) {
                villager.setVillagerData(villager.getVillagerData().setLevel(MASTER_LEVEL));
            }
            if (villager.getOffers().isEmpty()) {
                rebuildTrades(villager, profession, villager.getOffers());
            }
        }
        clampSingleUse(villager.getOffers());
    }

    /**
     * 单次交易钳制：全部条目 {@code maxUses=1}。收编入口（adopt）与扫描尾部共用，
     * 自动指派路（assign 后直接 return、不落尾部）同样在首轮即钳好，无多用途窗口。
     */
    private static void clampSingleUse(MerchantOffers offers) {
        for (MerchantOffer offer : offers) {
            ((MerchantOfferAccessor) offer).dimblend$setMaxUses(SINGLE_USE);
        }
    }

    /** 收编：定大师 + 全量重建交易 + 单次钳制 + 记附件。 */
    private static void adopt(Villager villager, VillagerProfession profession, String professionId) {
        if (villager.getVillagerData().getLevel() < MASTER_LEVEL) {
            villager.setVillagerData(villager.getVillagerData().setLevel(MASTER_LEVEL));
        }
        villager.setData(ExplorationAttachments.VILLAGER_PROFESSION.get(), professionId);
        rebuildTrades(villager, profession, villager.getOffers());
        clampSingleUse(villager.getOffers());
    }

    /**
     * 新生无业收编：随机指派职业（不含无业/傻子）→ 定大师 + 全量重建交易。
     * 指派后按原版转职礼仪刷新 brain 并广播绿色粒子；职业池为空（理论上不可能）
     * 则消费戳直接返回，保持无业。
     */
    private static void assignRandomProfessionAndAdopt(Villager villager) {
        VillagerProfession picked = randomProfession(villager);
        if (picked == null) {
            villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), false);
            return;
        }
        villager.setVillagerData(villager.getVillagerData().setProfession(picked));
        if (villager.level() instanceof ServerLevel serverLevel) {
            villager.refreshBrain(serverLevel);
            serverLevel.broadcastEntityEvent(villager, (byte) 14);
        }
        String professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(picked).toString();
        adopt(villager, picked, professionId);
        villager.setData(ExplorationAttachments.VILLAGER_FRESH.get(), false);
        // 批量繁殖/刷怪场景下 info 会刷屏，降为 debug（现场单只验证时开 debug 日志仍可见）
        DimBlend.LOGGER.debug("VillagerMaster: auto-assigned {} to new villager at {}",
                professionId, villager.blockPosition());
    }

    // 单服务端线程读写 + 注册表冻结后不变；datapack/mid-game 增补职业不刷新系接受。
    private static volatile List<VillagerProfession> assignablePool;

    /** 可指派职业池（全体注册职业减去无业/傻子）：注册表冻结后不变，懒初始化一次。 */
    private static VillagerProfession randomProfession(Villager villager) {
        List<VillagerProfession> pool = assignablePool;
        if (pool == null) {
            pool = BuiltInRegistries.VILLAGER_PROFESSION.stream()
                    .filter(p -> p != VillagerProfession.NONE && p != VillagerProfession.NITWIT)
                    .toList();
            assignablePool = pool;
        }
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(villager.getRandom().nextInt(pool.size()));
    }

    /** 全量重建：清空后逐级取 2 条（1–5 级），新人新口味与 mod 增补一并生效。 */
    private static void rebuildTrades(Villager villager, VillagerProfession profession, MerchantOffers offers) {
        offers.clear();
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel = VillagerTrades.TRADES.get(profession);
        if (byLevel == null) {
            return;
        }
        VillagerAccessor accessor = (VillagerAccessor) villager;
        for (int level = 1; level <= MASTER_LEVEL; level++) {
            VillagerTrades.ItemListing[] listings = byLevel.get(level);
            if (listings == null || listings.length == 0) {
                continue;
            }
            accessor.dimblend$addOffers(offers, listings, Math.min(2, listings.length));
        }
    }

    private VillagerMasterRules() {
    }
}
