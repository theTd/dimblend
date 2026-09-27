package dimblend.experience.compat.enderstorage;

import dev.ryanhcode.sable.Sable;
import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * G5 末影存储只能放在 sable 结构上（仅 rotating）：
 * {@code enderstorage:ender_chest} / {@code enderstorage:ender_tank} 的放置位置
 * 不在 sable 子层级内（放在地面等非列车/舰船结构处）一律不留块；创造模式豁免，
 * 机器/非玩家实体放置同样拦截。原版 {@code minecraft:ender_chest} 不在此列。
 *
 * <p>拦截方式沿用 F1（{@code PhysicsAssemblerGuard}）的「放行放置 + tick 末破坏返还」，
 * <b>不取消</b> {@code EntityPlaceEvent}：取消只回滚服务端，客户端预测已扣掉的物品
 * 得不到槽位纠正、表现为消失不返还（F1 实测结论）；事件回调期间放置事务尚未收尾，
 * 当场拆会与快照应用/回滚竞争，故登记后在本服务端 tick 末拆除。</p>
 *
 * <p>返还走方块自身掉落规则 {@link Block#getDrops}，不能像 F1 那样
 * {@code new ItemStack(block)}：EnderStorage 2.13.0.191 的 {@code BlockEnderStorage#getDrops}
 * 读 BE 频率写回物品（三色频率），私有频率另返还钥匙物品——裸 new 会把频率洗白。
 * 真人玩家（精确 {@code ServerPlayer} 类判据，FakePlayer 落入机器分支）拆除不掉落、
 * 掉落物直回背包（无提示）；机器/非玩家放置 {@code destroyBlock(true)} 按同一规则掉落。
 * 箱/罐内容存于频率全局存储而非 BE，拆除不丢内容。</p>
 *
 * <p>结构判定与 G2（{@code StructureBedGuard}）同口径：{@code Sable.HELPER.getContaining}
 * 查放置坐标归属子层级（贴结构面放置时坐标落在该结构的 plot 区）。
 * enderstorage 缺席时无目标方块、直接放行；sable 缺席时 fail-open 放行。
 * 对 {@code Sable.HELPER} 的引用只在守卫通过后触达，JVM 惰性解析——与 G2 同型。</p>
 *
 * <p><b>已知接受风险</b>：只拦 {@code EntityPlaceEvent} 路径；Create 装置解体、
 * 蓝图炮等直接 {@code setBlock} 写入的路径不触发事件，不拦。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class EnderStorageStructureGuard {

    /** 本 tick 待拆除的放置。登记时不拦截，tick 末尾放置事务收尾后再拆。 */
    private record PendingTeardown(ServerLevel level, BlockPos pos, Entity placer) {
    }

    private static final List<PendingTeardown> PENDING = new ArrayList<>();

    private static boolean isGuarded(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return EnderStorageBlockIds.isGuarded(key.getNamespace(), key.getPath());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!Config.ENDER_STORAGE_STRUCTURE_ONLY.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!ModList.get().isLoaded(EnderStorageBlockIds.MODID) || !ModList.get().isLoaded("sable")) {
            return;
        }
        // getPlacedBlock() 对非玩家实体返回放置前状态（BlockEvent 构造器差异），
        // 统一用快照的目标状态判定——与 F1 同口径
        if (!isGuarded(event.getBlockSnapshot().getCurrentState())) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Player player && player.isCreative()) {
            return;
        }
        BlockPos pos = event.getPos().immutable();
        if (Sable.HELPER.getContaining(level, (Vec3i) pos) != null) {
            return;
        }
        PENDING.add(new PendingTeardown(level, pos, entity));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<PendingTeardown> batch = List.copyOf(PENDING);
        PENDING.clear();
        for (PendingTeardown pending : batch) {
            teardown(pending);
        }
    }

    private static void teardown(PendingTeardown pending) {
        ServerLevel level = pending.level();
        BlockPos pos = pending.pos();
        BlockState state = level.getBlockState(pos);
        if (!isGuarded(state)) {
            return; // 放置被其他监听取消，或方块已被替换：无事可做
        }
        // 真人玩家用精确类判据：NeoForge FakePlayer（部署器等）继承 ServerPlayer，
        // instanceof 区分不了；真人必是原生 ServerPlayer，假代理一律按掉落处理。
        // 同 tick 内已下线的玩家背包不再落盘，同样改为原地掉落
        Entity placer = pending.placer();
        boolean realPlayer = placer != null && placer.getClass() == ServerPlayer.class && !placer.isRemoved();
        if (!realPlayer) {
            level.destroyBlock(pos, true, placer);
            return;
        }
        ServerPlayer player = (ServerPlayer) placer;
        // 返还物必须在拆除前按 BE 计算（频率存在 BE 上，拆除后即丢）
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> refunds = Block.getDrops(state, level, pos, blockEntity, player, ItemStack.EMPTY);
        // destroyBlock 统一走原版破坏路径：破碎粒子 + GameEvent.BLOCK_DESTROY + 流体恢复
        level.destroyBlock(pos, false, player);
        for (ItemStack refund : refunds) {
            player.getInventory().placeItemBackInInventory(refund);
        }
    }

    private EnderStorageStructureGuard() {
    }
}
