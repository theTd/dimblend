package dimblend.experience.compat.simulated;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.create.KineticComponentScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F2 simulated 便携引擎同 Create 动力网络互斥：一个连通的动力网络里最多一台。
 *
 * <p>目标 16 色 {@code simulated:<color>_portable_engine} 全算同类（以注册表名
 * {@code simulated} 命名空间 + {@code _portable_engine} 后缀识别，无编译依赖；
 * 裸 {@code simulated:portable_engine} 是 BlockEntityType id、不是方块）。</p>
 *
 * <p><b>“同网”按连通判，与转速无关</b>（用户拍板：网络不必转起来，零转速也照毁）。
 * Create 的 {@code KineticNetwork} 只在转起来后才存在，故不用 network id，改由
 * {@link KineticComponentScan} 按 Create 连接规则广搜连通分量；通电离合器等分轴隔断的两侧
 * 不算同网（静置语义见该类注释）。</p>
 *
 * <p>两条结算规则，都在服务端 tick 末执行、一律 {@code destroyBlock(true)} 按 loot 掉落、
 * 无豁免：</p>
 * <ol>
 * <li>实体放置（{@code EntityPlaceEvent}：玩家、机械手 FakePlayer 等）：新放引擎所在
 * 分量内已有其他引擎 → 新放的这台自毁（后来者让位）。按方块查连通、不依赖新 BE 是否已
 * 接入网络，故机械手在 BE tick 期间放置（新 BE 下一 tick 才首 tick）同样当拍判定。</li>
 * <li>任何动力块（重新）接入（{@code PortableEngineAttachMixin} 挂
 * {@code RotationPropagator.handleAdded}）：只标记该维度有变动；tick 末从该维度已加载的
 * 每台引擎出发扫连通分量，N≥2 台则随机留 1 台、其余全毁（机率均等、一次收敛）。
 * 覆盖：用传动杆/齿轮/皮带/链传动把两边接起来、离合器/变速箱红石切换、蓝图炮/装置解体/
 * 指令等不发放置事件的写入、区块加载（存量违规读档即收敛）、引擎起转（起转也会
 * {@code attachKinetics}）。</li>
 * </ol>
 *
 * <p>不做轮询（已砍，不要加回来）：只在有动力块接入的那一 tick 结算；维度内已加载引擎
 * 不足 2 台时连扫描都跳过。开关关闭期间放置登记直接丢弃，维度变动标记保留，
 * 重新打开后的第一个 tick 补结算。</p>
 *
 * <p>延迟到 tick 末的原因：接入钩子处 Create 的传播尚未跑完；在 BE tick 内同步拆引擎，
 * 引擎 tick 余下逻辑的 {@code setBlock(LIT)} 会把刚拆掉的方块写回来。
 * tick 末两问题都不存在。</p>
 *
 * <p>本类是常驻事件订阅者，方法签名不得出现 Create 类型（Create 缺席时 FML 反射扫描会
 * {@code NoClassDefFoundError}）；Create 相关逻辑全部在 {@link KineticComponentScan}。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class PortableEngineExclusivity {

    /**
     * 是否同类引擎注册名（纯函数，单测口径）：simulated 命名空间 +
     * _portable_engine 后缀（16 色全中；裸 portable_engine、
     * physics_assembler、engine_assembly 都不中）。
     */
    public static boolean isPortableEngineId(String namespace, String path) {
        return MODID.equals(namespace) && path.endsWith(SUFFIX);
    }

    /**
     * 是否同类引擎方块：simulated 命名空间 + _portable_engine 后缀（16 色全中，
     * physics_assembler 等其他 simulated 方块不中）。
     */
    public static boolean isPortableEngine(BlockState state) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return isPortableEngineId(key.getNamespace(), key.getPath());
    }

    /** 8 秒轮询间隔（tick）。轮询已砍：仅保留常量供文档/测试引用，不得用于调度。 */
    public static final int INTERVAL_TICKS = 160;

    /**
     * 同网 N 台引擎中要拆掉的那些：除 {@code survivorIndex} 外全部（纯函数，单测口径）。
     * 不足 2 台返回空表。结算时 {@code survivorIndex} 取均匀随机。
     */
    public static <T> List<T> victimsKeepingOne(List<T> engines, int survivorIndex) {
        if (engines.size() < 2) {
            return List.of();
        }
        List<T> victims = new ArrayList<>(engines);
        victims.remove(survivorIndex);
        return victims;
    }

    private static final String MODID = "simulated";
    private static final String SUFFIX = "_portable_engine";

    private PortableEngineExclusivity() {
    }

    private record PendingPlacement(ServerLevel level, BlockPos pos) {
    }

    /** 实体放置登记，tick 末按“后来者让位”结算。 */
    private static final List<PendingPlacement> PENDING_PLACEMENTS = new ArrayList<>();

    /** 本 tick 有动力块接入的维度，tick 末按“随机留一台”结算。 */
    private static final Set<ServerLevel> DIRTY_LEVELS = new LinkedHashSet<>();

    /**
     * 各维度已加载引擎位置（接入钩子登记，结算时惰性剔除已卸载/已拆除的）。
     * 只用作扫描起点与“不足 2 台跳过扫描”的快速判据，不是判定依据本身。
     */
    private static final Map<ServerLevel, Set<BlockPos>> LOADED_ENGINES = new HashMap<>();

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ModList.get().isLoaded(MODID) || !ModList.get().isLoaded("create")) {
            return;
        }
        if (!isPortableEngine(event.getBlockSnapshot().getCurrentState())) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING_PLACEMENTS.add(new PendingPlacement(level, event.getPos().immutable()));
        }
    }

    /**
     * 动力块（重新）接入时调用（{@code PortableEngineAttachMixin}，Create
     * {@code RotationPropagator.handleAdded} HEAD）：只登记，不扫描、不拆。
     * 开关不在此判——关闭期间照常登记，重新打开后补结算。
     */
    public static void onKineticAttached(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        DIRTY_LEVELS.add(serverLevel);
        if (isPortableEngine(state)) {
            LOADED_ENGINES.computeIfAbsent(serverLevel, key -> new HashSet<>()).add(pos.immutable());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_PLACEMENTS.isEmpty() && DIRTY_LEVELS.isEmpty()) {
            return;
        }
        if (!Config.PORTABLE_ENGINE_EXCLUSIVITY.get()) {
            PENDING_PLACEMENTS.clear();
            return;
        }
        // 先结算放置（后来者让位），再结算维度变动（随机留一）：同一台新引擎两条都会命中时，
        // 放置规则先把它拆掉，随机规则就不会误拆老引擎。
        if (!PENDING_PLACEMENTS.isEmpty()) {
            List<PendingPlacement> placements = List.copyOf(PENDING_PLACEMENTS);
            PENDING_PLACEMENTS.clear();
            for (PendingPlacement placement : placements) {
                settlePlacement(placement.level(), placement.pos());
            }
        }
        if (!DIRTY_LEVELS.isEmpty()) {
            List<ServerLevel> levels = List.copyOf(DIRTY_LEVELS);
            DIRTY_LEVELS.clear();
            for (ServerLevel level : levels) {
                settleLevel(level);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // 关服/维度卸载：不留旧 ServerLevel 引用（单人存档退出再进同一 JVM）
            PENDING_PLACEMENTS.removeIf(placement -> placement.level() == level);
            DIRTY_LEVELS.remove(level);
            LOADED_ENGINES.remove(level);
        }
    }

    private static void settlePlacement(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !isPortableEngine(level.getBlockState(pos))) {
            return; // 已被其他监听取消或替换
        }
        LOADED_ENGINES.computeIfAbsent(level, key -> new HashSet<>()).add(pos);
        List<BlockPos> engines = KineticComponentScan.collectMatching(
                level, pos, new HashSet<>(), PortableEngineExclusivity::isPortableEngine);
        if (engines.size() >= 2) {
            level.destroyBlock(pos, true);
        }
    }

    private static void settleLevel(ServerLevel level) {
        Set<BlockPos> tracked = LOADED_ENGINES.get(level);
        if (tracked == null) {
            return;
        }
        tracked.removeIf(pos -> !level.isLoaded(pos) || !isPortableEngine(level.getBlockState(pos)));
        if (tracked.isEmpty()) {
            LOADED_ENGINES.remove(level);
            return;
        }
        if (tracked.size() < 2) {
            return; // 尚未接入的新引擎（如机械手当拍放置）接入时会再标记本维度
        }
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : List.copyOf(tracked)) {
            List<BlockPos> engines = KineticComponentScan.collectMatching(
                    level, start, visited, PortableEngineExclusivity::isPortableEngine);
            if (engines.size() < 2) {
                continue;
            }
            for (BlockPos victim : victimsKeepingOne(engines, level.random.nextInt(engines.size()))) {
                if (level.isLoaded(victim) && isPortableEngine(level.getBlockState(victim))) {
                    level.destroyBlock(victim, true);
                }
            }
        }
    }
}
