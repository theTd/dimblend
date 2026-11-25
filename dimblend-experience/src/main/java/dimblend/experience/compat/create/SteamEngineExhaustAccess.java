package dimblend.experience.compat.create;

import java.util.Map;
import java.util.WeakHashMap;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * 蒸汽引擎 32 秒排气窗口的<b>跨类</b>状态通道（剩余 tick）。
 *
 * <p>为什么不能放在 {@code SteamEngineOverloadMixin} 的 {@code @Unique} 字段里：</p>
 * <ul>
 *   <li>排气状态要能被 {@code PoweredShaftBlock#stillValid}（防止轴被重新耦合成动力轴）、
 *       冷热兼容（排气的引擎当岩浆热源）和 NeoForge 破坏事件读到——它们都不在引擎的 mixin 里；</li>
 *   <li>还要能落盘（32 秒排气不能被"退出重进"清零）——写盘在
 *       {@code SmartBlockEntityExhaustNbtMixin} 里，同样在别的类。</li>
 * </ul>
 *
 * <p>状态按 {@link BlockEntity} 实例存（{@link WeakHashMap}：BE 被卸载/替换后条目自动消失，
 * 不泄漏）。<b>只在服务端主线程读写</b>（BE tick / 事件回调），不加锁——加锁只会掩盖跨线程误用。</p>
 *
 * <p>键 {@value #NBT_KEY} 由 NBT mixin 写入/读回；值是<b>剩余</b> tick（640 → 0）而不是年龄：
 * "还要烧多久"跨存档/重进语义不变，断轴时直接写 640。</p>
 */
public final class SteamEngineExhaustAccess {

    /** NBT 键：剩余排气 tick。 */
    public static final String NBT_KEY = "DimBlendExhaustRemaining";

    private static final Map<BlockEntity, Integer> REMAINING = new WeakHashMap<>();

    /** 设置剩余量；&lt;= 0 视为结束（一并移出表）。 */
    public static void setRemaining(SmartBlockEntity engine, int ticks) {
        if (ticks <= 0) {
            REMAINING.remove(engine);
        } else {
            REMAINING.put(engine, ticks);
        }
    }

    public static int remaining(SmartBlockEntity engine) {
        Integer ticks = REMAINING.get(engine);
        return ticks == null ? 0 : ticks;
    }

    public static void clear(SmartBlockEntity engine) {
        REMAINING.remove(engine);
    }

    public static boolean isExhausting(SmartBlockEntity engine) {
        return remaining(engine) > 0;
    }

    /** 年龄（断轴当 tick 为 0）：由剩余量反推，保证声音/粒子节律与"还要烧多久"永远一致。 */
    public static int age(int remainingTicks) {
        return Math.max(0, SteamEngineOverloadMath.EXHAUST_DURATION_TICKS - Math.max(0, remainingTicks));
    }

    /**
     * 该坐标上的蒸汽引擎是否正在排气。用 {@code getChunkSource().getChunkNow} 非阻塞取区块
     * （{@code dimblend-dev} §8.28）：事件回调可能落在未加载区块，绝不能用
     * {@code getChunk}/{@code hasChunkAt} 触发同步加载。
     */
    public static boolean isExhaustingAt(@Nullable BlockGetter level, BlockPos pos) {
        return loadedBlockEntity(level, pos) instanceof SmartBlockEntity smart && isExhausting(smart);
    }

    /** 非阻塞区块内 BE 查找（{@code getChunkNow} + {@code getBlockEntities}，不创建、不加载区块）。 */
    @Nullable
    public static BlockEntity loadedBlockEntity(@Nullable BlockGetter level, BlockPos pos) {
        LevelChunk chunk = chunkNow(level, new ChunkPos(pos));
        return chunk == null ? null : chunk.getBlockEntities().get(pos);
    }

    /** 非阻塞方块状态读取（{@code getChunkNow} + {@code LevelChunk#getBlockState}）；区块没加载返回 null。 */
    @Nullable
    public static net.minecraft.world.level.block.state.BlockState loadedBlockState(
            @Nullable BlockGetter level, BlockPos pos) {
        LevelChunk chunk = chunkNow(level, new ChunkPos(pos));
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    @Nullable
    private static LevelChunk chunkNow(@Nullable BlockGetter level, ChunkPos chunkPos) {
        if (!(level instanceof Level realLevel)) {
            return null;
        }
        return realLevel.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
    }

    private SteamEngineExhaustAccess() {
    }
}
