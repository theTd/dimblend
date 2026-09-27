package dimblend.experience.compat.simulated;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F2 simulated 便携引擎同 Create 动力网络互斥（用户拍板口径）。
 *
 * <p>目标 16 色 {@code simulated:<color>_portable_engine} 全算同类（以注册表名
 * {@code simulated} 命名空间 + {@code _portable_engine} 后缀识别，无编译依赖；
 * 裸 {@code simulated:portable_engine} 是 BlockEntityType id、不是方块）。
 * {@code simulated} 或 {@code create} 缺席时直接放行。</p>
 *
 * <p>两条触发（轮询已砍掉，不要加回来），目标都是整网只剩一台：</p>
 * <ol>
 * <li>放置瞬间：新放引擎若已连入含有同类引擎的动力网络（同 network id），
 * 登记者自己就是多余的那台——本 tick 末自毁（{@code destroyBlock(true)} 按 loot
 * 掉自身）。网络尚未建立时不毁——误伤放置顺序在前的无辜者是本规则唯一已知
 * 接受风险（8 秒轮询已砍，无二次收敛）。</li>
 * <li>自身起转：引擎 {@code speed} 0→非零（点火对外输出转速）时登记，
 * 本 tick 末枚举合网后全网同类引擎（含自身）；若有 N≥2 台则随机毁 N-1 台、
 * 只剩一台（{@code destroyBlock(true)}）。机率均等、登记者不豁免——
 * 用户拍板真随机，三台同网一次收敛，不留“下次起转再爆一台”的尾巴。</li>
 * </ol>
 *
 * <p>起转检查必须延迟到 tick 末结算，同步在 {@code applyNewSpeed} 里查必漏检：
 * mixin 注入点是方法 HEAD，此时 {@code setSpeed/setNetwork/attachKinetics}
 * 都还没跑（6.0.10-281 字节码：起转分支先 {@code setSpeed} 再
 * {@code setNetwork(createNetworkId())} 最后 {@code attachKinetics} 合网）——
 * 静置双机在 HEAD 时刻都无网（零转速 BE 之间
 * {@code RotationPropagator.propagateNewSource} 直接跳过，不建 source/network
 * 关系），枚举恒为空。延迟的第二个原因：同步在 BE tick 内自毁，tick 余下逻辑
 * 仍会 {@code setBlock(LIT, true)} 把刚炸掉的自己复活（掉落照掉、块也回来）。
 * tick 末两问题都消失：合网已完成、自毁不在任何 BE tick 内。</p>
 *
 * <p>同网判定走 Create 公共 API：{@code KineticBlockEntity#hasNetwork()} +
 * {@code #getOrCreateNetwork()}（{@code KineticNetwork#members} 键集），
 * 不碰 BE 内部字段、不碰 {@code TorquePropagator}。`getOrCreateNetwork()`
 * 在服务端对已入网 BE 返回现网、不建新网；未入网（{@code hasNetwork()==false}）
 * 时不调用（避免凭空建网）。BE 类型走公共父类
 * {@code KineticBlockEntity} 比对（便携引擎 BE 继承
 * {@code GeneratingKineticBlockEntity}），方块身份走注册表名比对——
 * 只有同类方块才进候选集。</p>
 *
 * <p>实现位置取舍：起转钩子挂 {@code GeneratingKineticBlockEntity}（Create 公共类，
 * 编译依赖已在 build.gradle，运行期 Create 必在场——simulated 本体依赖 Create）
 * 而非 simulated 的 {@code PortableEngineBlockEntity}（无编译依赖、jarjar 内嵌），
 * handler 内以方块注册表名收敛到 16 色引擎。mixin 目标 Create 故常驻
 * （simulated 缺席时方块名对不上、自然放行），plugin 仍按 create 在场性过滤，
 * 与 ItemDrain 三 mixin 同门。</p>
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
     * 整网 N 台留几台：恒为 1。N 台引擎一次收敛只剩一台（纯函数，单测口径）。
     */
    public static int survivorCount(int engineCount) {
        return engineCount >= 2 ? 1 : engineCount;
    }

    private static final String MODID = "simulated";
    private static final String SUFFIX = "_portable_engine";

    private PortableEngineExclusivity() {
    }

    /**
     * 放置瞬间检查：新放引擎若已连入含其他同类引擎的动力网络则自毁。
     *
     * <p>登记不拆、tick 末拆（与 F1 同硬约束：事件回调期间放置事务尚未收尾——
     * BE 尚未 attach、network 字段尚未建立，当场查必为无网而漏检）。
     * 沿用 F1 的 {@code ServerTickEvent.Post} 批量路径：事件只登记、
     * 每服务端 tick 末统一结算，BE 已 attach，{@code hasNetwork()} 可用。</p>
     */
    private record PendingCheck(ServerLevel level, BlockPos pos) {
    }

    private static final List<PendingCheck> PENDING = new ArrayList<>();

    /**
     * 起转 burst 待结算：只登记、tick 末枚举合网后拓扑再随机毁一台
     * （延迟原因见类注释：HEAD 时刻网络尚未合并 + tick 内同步自毁会被复活）。
     */
    private record PendingBurst(ServerLevel level, BlockPos pos) {
    }

    private static final List<PendingBurst> PENDING_BURSTS = new ArrayList<>();

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ModList.get().isLoaded(MODID) || !ModList.get().isLoaded("create")) {
            return;
        }
        if (!isPortableEngine(event.getBlockSnapshot().getCurrentState())) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.add(new PendingCheck(level, event.getPos()));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PENDING.isEmpty()) {
            List<PendingCheck> batch = List.copyOf(PENDING);
            PENDING.clear();
            for (PendingCheck pending : batch) {
                checkPlaced(pending.level(), pending.pos());
            }
        }
        if (!PENDING_BURSTS.isEmpty()) {
            List<PendingBurst> bursts = List.copyOf(PENDING_BURSTS);
            PENDING_BURSTS.clear();
            for (PendingBurst burst : bursts) {
                settleBurst(burst.level(), burst.pos());
            }
        }
    }

    private static void checkPlaced(ServerLevel level, BlockPos pos) {
        if (!Config.PORTABLE_ENGINE_EXCLUSIVITY.get()) {
            return;
        }
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!isPortableEngine(state)) {
            return; // 已被其他监听取消或替换
        }
        if (hasOtherEngineOnNetwork(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    /**
     * 自身起转（speed 0→非零）时调用：只登记，tick 末由 {@link #settleBurst}
     * 结算。这里只做轻守卫（服务端 + simulated 在场 + 方块仍是引擎），
     * 开关与网络拓扑留给结算时重判——登记与结算之间开关可能被改、方块可能被拆。
     */
    public static void onStartedGenerating(Level level, BlockPos selfPos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ModList.get().isLoaded(MODID)) {
            return;
        }
        if (!serverLevel.isLoaded(selfPos)) {
            return;
        }
        if (!isPortableEngine(serverLevel.getBlockState(selfPos))) {
            return; // 非引擎发电机直接返回
        }
        PendingBurst burst = new PendingBurst(serverLevel, selfPos);
        if (!PENDING_BURSTS.contains(burst)) {
            PENDING_BURSTS.add(burst);
        }
    }

    private static void settleBurst(ServerLevel level, BlockPos selfPos) {
        if (!Config.PORTABLE_ENGINE_EXCLUSIVITY.get()) {
            return;
        }
        if (!level.isLoaded(selfPos)) {
            return;
        }
        if (!isPortableEngine(level.getBlockState(selfPos))) {
            return; // 登记后被拆/被换：登记者自己已不在，无事可做
        }
        // 全网同类（含自身）：无序快照 + 登记者置首，保证登记者恒在候选内
        // （members 是 HashMap，迭代顺序不稳定；首位只是“含自身”的形状保证，
        // 不是优待——毁谁纯随机）。
        List<BlockPos> engines = enginesOnNetwork(level, selfPos);
        if (engines.size() < 2) {
            return;
        }
        // N 台留 1 台：Fisher-Yates 洗牌后尾部 N-1 台全毁，一次收敛。
        // 不用“随机毁一台等下次”：三台同网时一次起转只毁一台会剩两台，
        // 而第二台不一定再起转（已在转），尾巴永远收不掉。
        for (int i = engines.size() - 1; i > 0; i--) {
            int j = Math.floorMod(level.random.nextInt(), i + 1);
            BlockPos tmp = engines.get(i);
            engines.set(i, engines.get(j));
            engines.set(j, tmp);
        }
        for (int i = 1; i < engines.size(); i++) {
            BlockPos victim = engines.get(i);
            if (level.isLoaded(victim) && isPortableEngine(level.getBlockState(victim))) {
                level.destroyBlock(victim, true);
            }
        }
    }

    private static boolean hasOtherEngineOnNetwork(ServerLevel level, BlockPos pos) {
        return !otherEnginesOnNetwork(level, pos).isEmpty();
    }

    /**
     * 枚举同动力网络上的全部同类引擎位置（含自身，登记者置首）。
     *
     * <p>与 {@code #otherEnginesOnNetwork} 同口径（members 键集只读
     * {@code getBlockPos()}），区别仅在于含自身——起转 burst 需要全网 N 台
     * 一次收敛到 1 台，候选必须含登记者自己。</p>
     */
    private static List<BlockPos> enginesOnNetwork(ServerLevel level, BlockPos pos) {
        List<BlockPos> found = new ArrayList<>();
        found.add(pos);
        found.addAll(otherEnginesOnNetwork(level, pos));
        return found;
    }

    /**
     * 枚举同动力网络上的其他同类引擎位置。
     *
     * <p>Create 公共 API 口径（6.0.10-281 javap 核实）：
     * {@code KineticBlockEntity.hasNetwork()} 为真时
     * {@code getOrCreateNetwork().members} 键集即同网 BE（含自身）。
     * 未入网返回空集（静置未连网不算冲突）。members 键为 BE 实例——
     * 只读其 {@code getBlockPos()}，不触内部字段。</p>
     */
    private static List<BlockPos> otherEnginesOnNetwork(ServerLevel level, BlockPos pos) {
        List<BlockPos> found = new ArrayList<>();
        BlockEntity self = level.getBlockEntity(pos);
        if (!(self instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic)) {
            return found;
        }
        if (!kinetic.hasNetwork()) {
            return found;
        }
        com.simibubi.create.content.kinetics.KineticNetwork network = kinetic.getOrCreateNetwork();
        if (network == null) {
            return found;
        }
        Set<BlockPos> seen = new HashSet<>();
        for (com.simibubi.create.content.kinetics.base.KineticBlockEntity member : network.members.keySet()) {
            if (member == null || member == kinetic) {
                continue;
            }
            BlockPos memberPos = member.getBlockPos();
            if (memberPos.equals(pos) || !seen.add(memberPos)) {
                continue;
            }
            if (!level.isLoaded(memberPos)) {
                continue;
            }
            if (isPortableEngine(level.getBlockState(memberPos))) {
                found.add(memberPos);
            }
        }
        return found;
    }
}
