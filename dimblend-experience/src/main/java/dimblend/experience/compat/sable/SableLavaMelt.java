package dimblend.experience.compat.sable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * G6 岩浆熔毁（结构空位拟合的配对特性）：rotating 维度内，岩浆/岩浆流按 4 倍徒手
 * 速度熔毁位于 Sable 结构上的方块并使其掉落。
 *
 * <p>接触口径（用户拍板 2026-10-11）：熔岩格与方块投影体积（沿用体素化的旋转保守
 * 半 extent）AABB 距离 ≤ {@link LavaMeltMath#CONTACT_TOLERANCE} 即"被岩浆浇着"——
 * 同格（载具驶入熔岩，投影格内已有岩浆）与贴壳（熔岩从外侧流到拟合空位壳边，
 * 空位壳挡流体但 0.25 容差让壳边方块照常被浇）都覆盖。硬度只决定速度不设阈值：
 * 黑曜石级（50）约 62.5 秒持续浇淋才熔，等效抗岩浆；硬度负（基岩等）免疫。</p>
 *
 * <p>与拟合的配合：{@code VoidFitApplier#placeVoid} 在本开关开启时跳过熔岩格不再
 * 驱逐——熔岩留在投影格内持续浇淋，熔穿后投影收缩、空位拆除，熔岩向缺口推进，
 * 形成逐步熔穿；关闭则退回旧行为（熔岩与水同被驱逐，本调度整体停摆）。</p>
 *
 * <p>节律与预算：每 {@value #SCAN_TICKS} tick 扫一轮，单轮最多处理
 * {@value #VEHICLE_BUDGET_PER_SCAN} 个载具（逐维度游标 round-robin）；连续接触按
 * 距上次扫描的真实 tick 差累计，任何 cadence 下熔毁速率一致；新接触从 0 起算
 * （不预支发现前空窗），接触中断即清零（与徒手停手同型）。熔毁执行集中在枚举
 * 之后，不存在边遍历 plot 边改写的窗口。</p>
 *
 * <p>坐标系约定（字节码核实）：plot 区块是同一 {@code ServerLevel} 里的真实区块，
 * {@code VoidFitVoxelizer#forEachProjectedBlock} 产出的 plot 坐标与位姿输入同为
 * <b>全局存储坐标</b>——本类对 plot 方块的读写一律用 {@code level} + 全局坐标，
 * 读取即真实存储格，写删经 {@code LevelChunk#setBlockState} 走 Sable 的物理簿记
 * 钩子（质量/包围盒随熔毁更新）。{@code EmbeddedPlotLevelAccessor} 是中心相对坐标
 * （内部 {@code offset(centerBlock)}），喂全局坐标会错址，严禁混用。</p>
 *
 * <p>掉落：按方块自身掉落规则 {@code Block#getDrops}（无工具/时运/精准——岩浆不是
 * 矿工；loot ORIGIN 为真实存储格，原版 loot 表不消费位置谓词）在投影世界位置生成
 * 掉落物，并在该处播 {@link LevelEvent#PARTICLES_DESTROY_BLOCK} 破坏粒子+音效给玩家
 * 反馈。容器方块（箱子等）内容物先并入掉落再清空，避免 BE 移除回调把内容物撒到
 * 玩家够不到的 plot 存储区。capability 型（非 {@link Container} 接口）mod 容器
 * 不覆盖，内容物随 BE 移除丢失（接受项）。</p>
 *
 * <p>所有世界读取（熔岩探测）都带 {@code hasChunkAt} 守卫且在 tick 上下文执行——
 * 不走 ChunkEvent.Load 内的同步写入路径（见 docs/sable-void-fit-chunk-deadlock.md
 * 的事故口径），无邮箱嵌套等锁面。注册走 {@code DimBlend} 构造器的 Sable 门控
 * （{@code ModList.isLoaded("sable")} 才解析本类），与 {@link SableVoidFit} 同款
 * 惰性解析纪律。</p>
 */
public final class SableLavaMelt {

    /** 扫描周期（tick）。 */
    private static final int SCAN_TICKS = 5;
    /** 单轮扫描最多处理的载具数。 */
    private static final int VEHICLE_BUDGET_PER_SCAN = 2;

    /** 单个载具的熔毁进度：plot 格（BlockPos.asLong）→ 已累计进度，及上次扫描时刻。 */
    private static final class VehicleMelt {
        private final Long2FloatOpenHashMap progress = new Long2FloatOpenHashMap();
        private long lastScan = -1L;
    }

    /** 一次接触：plot 格（全局存储坐标）+ 当时的世界侧投影中心（熔毁反馈/掉落锚点）。 */
    private record Contact(long plotPos, double worldX, double worldY, double worldZ) {
    }

    private static final Map<ResourceKey<Level>, Map<UUID, VehicleMelt>> BY_DIMENSION = new HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> SCAN_CURSOR = new HashMap<>();
    /** 开关关闭期间经过的维度：恢复后首扫把这些维度的 lastScan 重置为当前时刻——
     * 停摆期不得贷入接触进度（"关闭后本特性停摆"语义：暂停后继续，不补发）。 */
    private static final Set<ResourceKey<Level>> RESUME_PENDING = new HashSet<>();

    /** 由 DimBlend 构造器在 Sable 在场时调用（只此一条入口）。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableLavaMelt::onLevelTick);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.isLoaded() || !Config.SABLE_LAVA_MELT.get()) {
            RESUME_PENDING.add(level.dimension());
            return;
        }
        if (level.getGameTime() % SCAN_TICKS != 0) {
            return;
        }
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        Map<UUID, VehicleMelt> melts = BY_DIMENSION.computeIfAbsent(level.dimension(),
                key -> new HashMap<>());
        if (RESUME_PENDING.remove(level.dimension())) {
            long resumedAt = level.getGameTime();
            for (VehicleMelt melt : melts.values()) {
                melt.lastScan = resumedAt;
            }
        }
        List<ServerSubLevel> current = container.getAllSubLevels();
        Set<UUID> alive = new HashSet<>();
        for (ServerSubLevel subLevel : current) {
            if (!subLevel.isRemoved()) {
                alive.add(subLevel.getUniqueId());
            }
        }
        melts.keySet().removeIf(id -> !alive.contains(id));
        if (current.isEmpty()) {
            return;
        }
        // 游标轮询：单轮预算内从上次停下的位置继续，多载具公平分摊
        int budget = VEHICLE_BUDGET_PER_SCAN;
        int cursor = SCAN_CURSOR.computeIfAbsent(level.dimension(), key -> 0);
        for (int step = 0; step < current.size() && budget > 0; step++) {
            ServerSubLevel subLevel = current.get((cursor + step) % current.size());
            if (subLevel.isRemoved()) {
                continue;
            }
            budget--;
            scanVehicle(level, subLevel, melts.computeIfAbsent(subLevel.getUniqueId(),
                    id -> new VehicleMelt()));
        }
        SCAN_CURSOR.put(level.dimension(), (cursor + VEHICLE_BUDGET_PER_SCAN) % current.size());
    }

    private static void scanVehicle(ServerLevel level, ServerSubLevel subLevel, VehicleMelt melt) {
        long now = level.getGameTime();
        long elapsed = melt.lastScan < 0L ? SCAN_TICKS : now - melt.lastScan;
        melt.lastScan = now;
        LongOpenHashSet contacted = new LongOpenHashSet();
        List<Contact> contacts = new ArrayList<>();
        VoidFitVoxelizer.forEachProjectedBlock(subLevel, (plotPos, state, wx, wy, wz, hx, hy, hz) -> {
            float hardness = state.getDestroySpeed(level, plotPos);
            if (!LavaMeltMath.meltable(hardness)) {
                return;
            }
            if (!touchesLava(level, wx, wy, wz, hx, hy, hz)) {
                return;
            }
            long key = plotPos.asLong();
            contacted.add(key);
            contacts.add(new Contact(key, wx, wy, wz));
        });
        // 接触中断的进度清零（停手重置）。累计口径：仅在"上轮扫描也有接触"时按真实
        // 间隔累计——新接触从 0 起算，不把发现前的空窗当作既成接触（否则接触刚建立
        // 就预支整段扫描间隔、熔毁提前发生）；硬度 0 发现即熔
        melt.progress.keySet().removeIf((long key) -> !contacted.contains(key));
        List<Contact> melting = new ArrayList<>();
        for (Contact contact : contacts) {
            BlockPos plotPos = BlockPos.of(contact.plotPos());
            BlockState state = level.getBlockState(plotPos);
            float hardness = state.getDestroySpeed(level, plotPos);
            double rate = LavaMeltMath.perTickProgress(hardness, state.requiresCorrectToolForDrops());
            if (Double.isInfinite(rate)) {
                melting.add(contact);
                continue;
            }
            boolean continuing = melt.progress.containsKey(contact.plotPos());
            double next = melt.progress.getOrDefault(contact.plotPos(), 0.0F)
                    + (continuing ? elapsed * rate : 0.0D);
            if (next >= 1.0D) {
                melting.add(contact);
            } else {
                melt.progress.put(contact.plotPos(), (float) next);
            }
        }
        for (Contact contact : melting) {
            meltBlock(level, contact);
            melt.progress.remove(contact.plotPos());
        }
    }

    /** 方块投影体积（中心 w、半 extent h）外扩 {@link LavaMeltMath#CONTACT_TOLERANCE} 内有熔岩格。 */
    private static boolean touchesLava(ServerLevel level,
            double wx, double wy, double wz, double hx, double hy, double hz) {
        double expand = LavaMeltMath.CONTACT_TOLERANCE;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int x0 = (int) Math.floor(wx - hx - expand);
        int x1 = (int) Math.floor(wx + hx + expand);
        int y0 = Math.max(minY, (int) Math.floor(wy - hy - expand));
        int y1 = Math.min(maxY, (int) Math.floor(wy + hy + expand));
        int z0 = (int) Math.floor(wz - hz - expand);
        int z1 = (int) Math.floor(wz + hz + expand);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    pos.set(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    // 岩浆与岩浆流同列（LiquidBlock + lava 流体标签），同型 mod 流体不管
                    if (state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void meltBlock(ServerLevel level, Contact contact) {
        BlockPos plotPos = BlockPos.of(contact.plotPos());
        BlockState state = level.getBlockState(plotPos);
        if (state.isAir()) {
            return;
        }
        BlockPos worldPos = BlockPos.containing(contact.worldX(), contact.worldY(), contact.worldZ());
        BlockEntity blockEntity = level.getBlockEntity(plotPos);
        // 掉落按方块自身规则（无工具/时运/精准）；容器内容物并入并清空，
        // 否则 BE 移除回调会把内容物撒到 plot 存储区（玩家够不到）
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, plotPos, blockEntity));
        if (blockEntity instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    drops.add(stack.copy());
                }
            }
            container.clearContent();
        }
        level.removeBlock(plotPos, false);
        level.levelEvent(null, LevelEvent.PARTICLES_DESTROY_BLOCK, worldPos, Block.getId(state));
        for (ItemStack drop : drops) {
            Block.popResource(level, worldPos, drop);
        }
    }

    private SableLavaMelt() {
    }
}
