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
import dimblend.experience.DimBlend;
import dimblend.experience.exploration.RotatingDimension;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
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
 * G6 岩浆熔毁（结构空位拟合的配对特性）：rotating 维度内，岩浆/岩浆流按
 * {@code sableLavaMeltSpeed} 倍徒手速度（默认
 * {@value LavaMeltMath#DEFAULT_SPEED_MULTIPLIER} 倍，/dbx 调参面板即时可调）熔毁位于
 * Sable 结构上的方块并使其掉落。
 *
 * <p>接触口径（用户拍板 2026-10-11）：熔岩格与方块投影体积（沿用体素化的旋转保守
 * 半 extent）AABB 距离 ≤ {@link LavaMeltMath#CONTACT_TOLERANCE} 即"被岩浆浇着"——
 * 同格（载具驶入熔岩，投影格内已有岩浆）与贴壳（熔岩从外侧流到拟合空位壳边，
 * 空位壳挡流体但 0.25 容差让壳边方块照常被浇）都覆盖。柱顶另向上多收一格
 * （2026-10-26，见 {@link #touchesLava}）：拟合格是保守覆盖，熔岩停在柱顶空位上时
 * 可能比方块 AABB 高出近一格，只按 AABB+0.25 会把"浇在车顶上"的熔岩漏判，车顶就
 * 无法从上方开始熔。硬度只决定速度不设阈值：黑曜石级（50）默认倍率下约 62.5 秒
 * 持续浇淋才熔，等效抗岩浆；硬度负（基岩等）免疫。</p>
 *
 * <p>免疫清单（2026-10-14 增补）：{@link #LAVA_MELT_IMMUNE} 标签内的方块一律不熔
 * （不进接触、不累计进度、不掉落；中途改标签，存量进度按接触中断自然清零）。标签
 * 继承 {@code #create:non_breakable}——打包时标为"机械不可破坏"的车辆关键部件
 * （simurail 转向架、自动车钩）：它们靠硬度以外的口径防破坏（生存攻击载具方块在
 * Sable 里只产生拳击冲量、不路由破坏；Create 机械臂/钻头遵守 non_breakable 清单），
 * 本特性直改 plot 存储会绕过这些口径，故显式豁免同一张清单。</p>
 *
 * <p>与拟合的配合（2026-10-26 安全柱顶 + 封口格）：{@code VoidFitApplier#placeVoid}
 * 在本开关开启时只保留"安全柱顶"的熔岩——该格对每个 target 它的载具都是柱顶、且
 * 正下方同柱下一格仍在 target 内（实体投影，或 {@code VoidFitColumnTops#sealCellsFor}
 * 给下方悬空柱顶补的封口格），并且正下方世界格此刻确实挡熔岩（structure_void 或
 * blocksMotion 方块）。单格柱/悬空柱顶由封口格补上正下方一格，熔岩留在车顶线同格
 * 接触、不能从完整船壳漏下去；柱内更低的投影格照常驱逐成空位（舱内不积岩浆、不把
 * 流经导向他处）。任一条件不成立即驱逐，靠停在车顶拟合面上的熔岩接触浇熔（柱顶 +1
 * 格接触口径）。熔穿后投影收缩、空位与封口格拆除，熔岩向缺口推进，形成逐层向下的
 * 熔穿；舱内空气不在体素化投影内，若熔穿层下方是空腔，熔岩经熔出的洞进入空腔
 * （原版流体行为），仍被下一层投影挡住，不会从完整船壳穿过。关闭则退回旧行为
 * （熔岩与水同被驱逐，本调度整体停摆）。</p>
 *
 * <p>节律与预算：每 {@value #SCAN_TICKS} tick 扫一轮，单轮最多处理
 * {@code Config#SABLE_LAVA_MELT_SCAN_BUDGET} 个载具（默认 2，逐维度游标 round-robin）；连续接触按
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
 * <p>浇淋音效：熔毁进行中（本轮未熔、进度仍在累计）的方块每轮扫描在其世界侧投影
 * 位置播一次 {@code block.fire.extinguish}（原版灭火口径：音量 0.5、音高 2.6±0.4
 * 随机，同 {@code BucketItem} 灭火）——持续浇淋即持续嘶声，熔穿瞬间的收尾反馈仍是
 * 破坏粒子/音效。必须播世界坐标：服务端 {@code playSound} 不做 plot→世界路由，
 * 播在 plot 存储格玩家听不见（{@code PhysicsBogeyBrakeSoundMixin} 的事故口径），
 * 投影中心与破坏粒子/掉落同锚。</p>
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

    /**
     * 熔毁免疫标签（{@code data/dimblend_experience/tags/block/lava_melt_immune.json}）：
     * 继承 {@code #create:non_breakable}——打包清单里"靠硬度以外口径防机械破坏"的
     * 车辆关键部件（转向架/自动车钩）岩浆同样熔不动。纯 TagKey 标识符、不引用 Create
     * 类（Create 是可选依赖）；嵌套目标由本 mod 自带数据文件定义、始终在场，其内
     * simurail 条目为可选项，simurail 缺席时跳过、标签为空。
     * （id 字面量在 {@code SableLavaMeltGameTests#lavaMeltImmuneTagIsBound} 有镜像
     * 断言，改名须同步。）
     */
    private static final TagKey<Block> LAVA_MELT_IMMUNE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "lava_melt_immune"));

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
        // Named profiler section, so /dimblend slowticks can attribute this listener.
        event.getLevel().getProfiler().push("dimblend:lava_melt");
        try {
            tickLevel(event);
        } finally {
            event.getLevel().getProfiler().pop();
        }
    }

    private static void tickLevel(LevelTickEvent.Post event) {
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
        int budget = Config.SABLE_LAVA_MELT_SCAN_BUDGET.get();
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
        SCAN_CURSOR.put(level.dimension(), (cursor + Config.SABLE_LAVA_MELT_SCAN_BUDGET.get()) % current.size());
    }

    private static void scanVehicle(ServerLevel level, ServerSubLevel subLevel, VehicleMelt melt) {
        long now = level.getGameTime();
        long elapsed = melt.lastScan < 0L ? SCAN_TICKS : now - melt.lastScan;
        melt.lastScan = now;
        // 倍率逐次扫描读配置：/dbx 面板或改文件后的新值下一轮即生效；已累进度不清零，
        // 后续按新速率累计（与徒手挖掘中途换工具同型）
        double speedMultiplier = Config.SABLE_LAVA_MELT_SPEED.get();
        LongOpenHashSet contacted = new LongOpenHashSet();
        List<Contact> contacts = new ArrayList<>();
        VoidFitVoxelizer.forEachProjectedBlock(subLevel, (plotPos, state, wx, wy, wz, hx, hy, hz) -> {
            float hardness = state.getDestroySpeed(level, plotPos);
            if (!LavaMeltMath.meltable(hardness)) {
                return;
            }
            if (state.is(LAVA_MELT_IMMUNE)) {
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
            double rate = LavaMeltMath.perTickProgress(hardness, state.requiresCorrectToolForDrops(),
                    speedMultiplier);
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
                playSizzle(level, contact);
            }
        }
        for (Contact contact : melting) {
            meltBlock(level, contact);
            melt.progress.remove(contact.plotPos());
        }
    }

    /**
     * 熔毁浇淋音：被浇方块每轮扫描播一次原版灭火嘶声（口径同 {@code BucketItem}
     * 灭火：音量 0.5、音高 2.6±0.4 随机）。位置必须用世界侧投影坐标——plot 存储
     * 格在远处真实区块里，播在那儿玩家听不见（服务端 {@code playSound} 不做
     * sublevel 坐标路由，见类注释）。
     */
    private static void playSizzle(ServerLevel level, Contact contact) {
        level.playSound(null, contact.worldX(), contact.worldY(), contact.worldZ(),
                SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F,
                2.6F + (level.random.nextFloat() - level.random.nextFloat()) * 0.8F);
    }

    /**
     * 方块投影体积（中心 w、半 extent h）外扩 {@link LavaMeltMath#CONTACT_TOLERANCE} 内有熔岩格。
     *
     * <p>柱顶（2026-10-26）额外把保守覆盖最高格的再上一格纳入：体素化按
     * {@code floor(w±h)} 取保守覆盖格，熔岩停在柱顶空位上方时，其格面比方块 AABB
     * 顶面最多高近一格（取决于亚格偏移），只按 AABB+0.25 扫描会漏判"浇在车顶上"的
     * 熔岩，车顶就永远等不到第一次接触。只向上多收一格：侧向/下方仍是 0.25 口径。
     * 非柱顶格多收的这格在投影内（空位），按不变式不会有熔岩，多收无效但无害。</p>
     */
    private static boolean touchesLava(ServerLevel level,
            double wx, double wy, double wz, double hx, double hy, double hz) {
        double expand = LavaMeltMath.CONTACT_TOLERANCE;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int x0 = (int) Math.floor(wx - hx - expand);
        int x1 = (int) Math.floor(wx + hx + expand);
        int y0 = Math.max(minY, (int) Math.floor(wy - hy - expand));
        int topCovered = (int) Math.floor(wy + hy);
        int y1 = Math.min(maxY, Math.max((int) Math.floor(wy + hy + expand), topCovered + 1));
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
        // 枚举到执行之间，同批先熔方块的邻接更新可能改写本格（如双箱解体）：重读后复核
        if (state.isAir() || state.is(LAVA_MELT_IMMUNE)) {
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
