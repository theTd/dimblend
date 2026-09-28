package dimblend.carwash.server;

import dimblend.carwash.chassis.ChassisGrimeRules;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 喷淋清洗批处理：命中回调只把车架坐标去重记下（喷淋每刻会对同一块回调很多次），
 * 每 0.5 秒在同一刻结算，每块被命中的车架算一次清洗。换图同步集中在结算刻，
 * 洗车时客户端每区段每秒至多重建两次网格。
 */
public final class ChassisSprayWashQueue {

    private static final Map<ServerLevel, LongOpenHashSet> PENDING = new WeakHashMap<>();

    /** 喷淋命中车架（仅服务端主线程入队，其余线程的回调丢弃）。 */
    public static void enqueue(ServerLevel level, BlockPos pos) {
        if (!level.getServer().isSameThread()) {
            return;
        }
        PENDING.computeIfAbsent(level, key -> new LongOpenHashSet()).add(pos.asLong());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % ChassisGrimeRules.SPRAY_BATCH_INTERVAL_TICKS != 0) {
            return;
        }
        LongOpenHashSet hits = PENDING.remove(level);
        if (hits == null) {
            return;
        }
        for (LongIterator it = hits.iterator(); it.hasNext(); ) {
            ChassisWashing.washOnce(level, BlockPos.of(it.nextLong()));
        }
    }

    private ChassisSprayWashQueue() {
    }
}
