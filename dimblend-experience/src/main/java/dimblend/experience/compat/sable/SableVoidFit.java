package dimblend.experience.compat.sable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

/**
 * 结构空位拟合调度入口（G3 配套）：rotating 维度内让 Sable 载具的世界投影体积始终被
 * {@code minecraft:structure_void} 顶住，水不会流进"船体内部"（原版
 * {@code FlowingFluid#canHoldFluid} 硬编码排除 structure_void）。
 *
 * <p>节律与预算（性能约束全在本类收口）：</p>
 * <ul>
 * <li>每 {@code Config#SABLE_VOID_FIT_REFIT_TICKS} tick 扫描一次（默认 5，即 4Hz）；</li>
 * <li>单次扫描最多 refit {@link #VEHICLE_BUDGET_PER_SCAN} 个载具，候选按 重试优先 →
 *     新载具 → 位姿超阈值 → 自愈到期 排序，超出预算的留到下轮（round-robin 由重试集合
 *     承载）；</li>
 * <li>位姿阈值：平移 {@value #TRANSLATION_THRESHOLD} 格或旋转约
 *     {@value #ROTATION_THRESHOLD_DEG}° 才触发重算，静止载具每轮只有 O(载具数) 次
 *     位姿比较；</li>
 * <li>慢速自愈：每 {@link #VERIFY_SCANS} 个扫描周期强制 refit 一次并开启逐格核验，
 *     吸收爆炸/玩家顶掉等外部漂移。</li>
 * </ul>
 *
 * <p>注册走 {@code DimBlend} 构造器的 Sable 门控（{@code ModList.isLoaded("sable")} 后
 * 才解析本类），与 {@link SableWorldPosition} 同款惰性解析纪律。</p>
 *
 * <p>已知限制：Create 装配体/蓝图若把拟合空位圈进 contraption，空位会随组装落进
 * contraption 存储、重新放置时成为不受 tracker 管理且会正常落盘的 structure_void——
 * 需装配范围与行驶载具重叠，极小众，按接受处理。结构方块模板捕获空位则无害
 * （模板语义下 structure_void = 加载时跳过）。</p>
 */
public final class SableVoidFit {

    /** 慢速自愈周期（按扫描次数计：20 扫描 × 默认 5 tick = 100 tick）。 */
    public static final int VERIFY_SCANS = 20;
    /** 单次扫描最多 refit 的载具数。 */
    private static final int VEHICLE_BUDGET_PER_SCAN = 2;
    private static final double TRANSLATION_THRESHOLD = 0.5;
    private static final double ROTATION_THRESHOLD_DEG = 2.5;
    private static final double TRANSLATION_THRESHOLD_SQR = TRANSLATION_THRESHOLD * TRANSLATION_THRESHOLD;
    /** |q1·q2| 下限：四元数点积偏离 1 超过 ~2.5° 视为转动。 */
    private static final double ROTATION_DOT_MIN =
            Math.cos(Math.toRadians(ROTATION_THRESHOLD_DEG));

    /** 上次 refit 因预算未写完、下轮必须重试的载具（按维度）。 */
    private static final Map<ResourceKey<Level>, Set<UUID>> RETRY = new HashMap<>();

    /** 由 DimBlend 构造器在 Sable 在场时调用（只此一条入口）。 */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableVoidFit::onLevelTick);
        NeoForge.EVENT_BUS.addListener(SableVoidFit::onChunkLoad);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.isLoaded() || !Config.SABLE_STRUCTURE_VOID_FIT.get()) {
            return;
        }
        int interval = Config.SABLE_VOID_FIT_REFIT_TICKS.get();
        if (level.getGameTime() % interval != 0) {
            return;
        }
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        VoidFitTracker tracker = VoidFitTracker.of(level.dimension());
        Set<UUID> retry = RETRY.computeIfAbsent(level.dimension(), key -> new LinkedHashSet<>());

        List<ServerSubLevel> current = container.getAllSubLevels();
        Set<UUID> alive = new HashSet<>();
        for (ServerSubLevel subLevel : current) {
            if (!subLevel.isRemoved()) {
                alive.add(subLevel.getUniqueId());
            }
        }
        // 载具消失（拆除/跨维度/驶出）：按 materialized 拆掉已写入的空位。
        // 先收集再处理——removeAll 会改动 tracker.vehicles，不能边遍历边删。
        // removeAll 受写入预算限制，拆不完的条目保留在 tracker，下轮扫描自然续拆
        List<UUID> gone = new ArrayList<>();
        for (UUID id : tracker.vehicles().keySet()) {
            if (!alive.contains(id)) {
                gone.add(id);
            }
        }
        for (UUID id : gone) {
            VoidFitApplier.removeAll(level, tracker, id);
            retry.remove(id);
        }
        // 已在 retry 里但已从世界消失的 id 清掉（deferred 的新载具在下次扫描前被拆的情形）
        retry.removeIf(id -> !alive.contains(id));

        int vehicleBudget = VEHICLE_BUDGET_PER_SCAN;
        List<ServerSubLevel> deferred = new ArrayList<>();
        // 重试集合优先；refit 对无条目载具自动建账，retry 里的新载具不会空烧预算
        if (!retry.isEmpty()) {
            for (ServerSubLevel subLevel : current) {
                if (retry.contains(subLevel.getUniqueId()) && vehicleBudget > 0) {
                    vehicleBudget--;
                    if (VoidFitApplier.refit(level, tracker, subLevel, true)) {
                        retry.remove(subLevel.getUniqueId());
                    }
                }
            }
        }
        for (ServerSubLevel subLevel : current) {
            UUID id = subLevel.getUniqueId();
            if (subLevel.isRemoved() || retry.contains(id)) {
                continue;
            }
            VoidFitTracker.VehicleFit fit = tracker.vehicles().get(id);
            boolean isNew = fit == null;
            boolean verifyDue = !isNew && --fit.verifyCountdown <= 0;
            if (!isNew && !verifyDue && !movedEnough(fit, subLevel)) {
                continue;
            }
            if (vehicleBudget <= 0) {
                deferred.add(subLevel);
                continue;
            }
            vehicleBudget--;
            if (!VoidFitApplier.refit(level, tracker, subLevel, verifyDue)) {
                retry.add(id);
            }
        }
        // 超预算的候选下轮优先（与位姿阈值无关）
        for (ServerSubLevel subLevel : deferred) {
            retry.add(subLevel.getUniqueId());
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.isLoaded() || !Config.SABLE_STRUCTURE_VOID_FIT.get()) {
            return;
        }
        VoidFitTracker tracker = VoidFitTracker.peek(level.dimension());
        if (tracker == null) {
            return;
        }
        LongSet cells = tracker.targetCellsOfChunk(event.getChunk().getPos());
        if (cells == null || cells.isEmpty()) {
            return;
        }
        VoidFitApplier.placeInChunk(level, tracker, cells);
    }

    private static boolean movedEnough(VoidFitTracker.VehicleFit fit, ServerSubLevel subLevel) {
        Vector3dc position = subLevel.logicalPose().position();
        if (fit.lastPosition.distanceSquared(position.x(), position.y(), position.z())
                > TRANSLATION_THRESHOLD_SQR) {
            return true;
        }
        Quaterniondc orientation = subLevel.logicalPose().orientation();
        double dot = Math.abs(fit.lastOrientation.dot(orientation));
        return dot < ROTATION_DOT_MIN;
    }

    private SableVoidFit() {
    }
}
