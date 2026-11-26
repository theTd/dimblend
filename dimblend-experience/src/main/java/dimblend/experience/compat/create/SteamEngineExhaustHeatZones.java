package dimblend.experience.compat.create;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * "正在喷高温蒸汽的蒸汽引擎"的<b>纯内存</b>位置登记表（2026-11-26 新增）。
 *
 * <p><b>为什么不用原来的 {@code SteamEngineExhaustAccess.isExhaustingAt}</b>：
 * 那条链路是 {@code (Level, BlockPos) → getChunkNow → LevelChunk#getBlockEntity →
 * 服务端 WeakHashMap<BlockEntity, Integer>}，即"温度判据"依赖<b>能当场解析出那个 BE 实例</b>。
 * 而 Cold Sweat 2.4.2 的 {@code BlockTempModifier#calculate} 在 Sable 子层级里会用
 * {@code DummyEntity} 在 <b>plot 坐标</b>上跑 {@code isValid(level, pos, state)}
 * （{@code SublevelBlockTempModifier} → {@code CompatManager.Sable.transformWorldToSubl}），
 * 查询的坐标空间/线程上下文与引擎自己的 tick 不一定同一份；{@code ServerChunkCache#getChunkNow}
 * 还有 {@code Thread.currentThread() != mainThread → null} 的硬守卫。
 * 于是"引擎确实在喷汽，但温度查询那条路径解析不到它"，高温就表现为"没生效"。</p>
 *
 * <p><b>本类把判据换成一个不依赖任何上下文的 O(1) 查表</b>：引擎自己的服务端 tick
 * 每 tick {@link #refresh} 一次（写 {@code (维度, BlockPos.asLong()) → 过期 gameTime}），
 * 温度查询只读这张表。维度 + 坐标就是完整身份，不需要区块、不需要 BE 实例、不需要线程一致。
 * TTL（{@link #TTL_TICKS}）保证引擎停 tick（区块卸载/存档退出）后 2 秒内自动失效，
 * 不会留下"幽灵热源"。</p>
 *
 * <p>线程：写入只发生在服务端主线程；读取可能来自客户端（Cold Sweat 客户端不算温度，
 * 但没有副作用地读一下也无妨）。故整表 {@code synchronized}——每 tick 只有个位数次写、
 * 读只对 {@code create:steam_engine} 方块发生，代价在噪声级。</p>
 */
public final class SteamEngineExhaustHeatZones {

    /** 登记有效时长（tick）。引擎每 tick 续期一次，2 秒不续即失效。 */
    public static final int TTL_TICKS = 40;

    /** 过期条目清扫周期（以 refresh 次数计）。 */
    private static final int SWEEP_EVERY_REFRESHES = 400;

    /** 维度 → (BlockPos.asLong() → 过期时刻 gameTime)。 */
    private static final Map<ResourceKey<Level>, Map<Long, Long>> ZONES = new HashMap<>();

    private static int refreshCounter;

    /** 登记/续期：某个引擎此刻正在排气（由引擎自己的服务端 tick 每 tick 调用）。 */
    public static void refresh(Level level, BlockPos pos) {
        long expireAt = level.getGameTime() + TTL_TICKS;
        synchronized (ZONES) {
            ZONES.computeIfAbsent(level.dimension(), key -> new HashMap<>())
                    .put(pos.asLong(), expireAt);
            if (++refreshCounter >= SWEEP_EVERY_REFRESHES) {
                refreshCounter = 0;
                sweep(level.getGameTime());
            }
        }
    }

    /** 立刻撤销登记（排气窗口结束 / 开关关闭时调用；TTL 只是兜底）。 */
    public static void clear(Level level, BlockPos pos) {
        synchronized (ZONES) {
            Map<Long, Long> zone = ZONES.get(level.dimension());
            if (zone != null) {
                zone.remove(pos.asLong());
            }
        }
    }

    /** 该位置此刻是否在排气窗口内（温度查询唯一入口）。 */
    public static boolean isExhausting(Level level, BlockPos pos) {
        long now = level.getGameTime();
        synchronized (ZONES) {
            Map<Long, Long> zone = ZONES.get(level.dimension());
            if (zone == null) {
                return false;
            }
            Long expireAt = zone.get(pos.asLong());
            if (expireAt == null) {
                return false;
            }
            if (expireAt < now) {
                zone.remove(pos.asLong()); // 惰性清理
                return false;
            }
            return true;
        }
    }

    /** 清空（仅测试/换维度重置用）。 */
    public static void clearAll() {
        synchronized (ZONES) {
            ZONES.clear();
            refreshCounter = 0;
        }
    }

    private static void sweep(long now) {
        ZONES.values().removeIf(zone -> {
            zone.values().removeIf(expireAt -> expireAt < now);
            return zone.isEmpty();
        });
    }

    private SteamEngineExhaustHeatZones() {
    }
}
