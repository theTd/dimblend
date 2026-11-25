package dimblend.blocks.compat.firefighting;

import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayBlockInteraction;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayFluidType;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayHitContext;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayInteractionRegistry;

import dimblend.blocks.fragile.WaterBucketLavaQuench;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 消防模组（{@code createfirefightingadd}）喷头水雾浇岩浆 = 蓝冰效果。
 *
 * <p>只在 {@code ModList.get().isLoaded("createfirefightingadd")} 时由主类调用
 * {@link #register()}——本类方法体内硬引用消防模组类型，JVM 惰性解析，缺席时类不会加载。</p>
 *
 * <p>过滤严格四条（参见 {@link LavaSprayQuenchMath#shouldQueue}）：</p>
 * <ul>
 *   <li>服务端；</li>
 *   <li>{@code direction() != null}——消防模组的 4 格空气球扫掠与路径扫掠样本
 *       构造 {@code NozzleSprayHitContext} 时 direction 传 {@code null}（实测
 *       {@code AbstractSprayDeviceBlockEntity} 第 1165/1193 行），必须排除，否则一发
 *       水雾就会把周围整片岩浆按"面"处理；</li>
 *   <li>{@code !ignited()}——点燃的水雾（火焰喷头）不算冷却；</li>
 *   <li>流体是<b>水</b>（{@code NozzleSprayFluidType.WATER}，等价于原 API 的
 *       {@code FluidBehavior.WATER}；牛奶/药水/龙息一概不冷却岩浆），且命中格是纯岩浆格。</li>
 * </ul>
 *
 * <p>命中不立即冷却，交给 {@link LavaSprayQuenchQueue} 做表面优先去重 + 每 5gt 节流。</p>
 */
public final class NozzleLavaQuench implements NozzleSprayBlockInteraction {

    /**
     * 注册喷头交互 + 挂 {@link LevelTickEvent.Post} flush。可重复调用（多入口守卫）。
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NozzleSprayInteractionRegistry.register(new NozzleLavaQuench());
        NeoForge.EVENT_BUS.addListener(NozzleLavaQuench::onLevelTick);
    }

    private static boolean registered;

    @Override
    public boolean shouldReceive(NozzleSprayHitContext context) {
        if (!(context.level() instanceof ServerLevel)) {
            return false;
        }
        return LavaSprayQuenchMath.shouldQueue(
                true,
                context.direction() != null,
                context.ignited(),
                context.fluidType() == NozzleSprayFluidType.WATER,
                WaterBucketLavaQuench.isPureLavaCell(context.state(), context.state().getFluidState()));
    }

    @Override
    public void onHit(NozzleSprayHitContext context) {
        if (!(context.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        LavaSprayQuenchQueue.offer(serverLevel, context.origin(), context.hitLocation(), context.direction(),
                context.pos());
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.getGameTime() % LavaSprayQuenchMath.BURST_INTERVAL_TICKS != 0L) {
                return;
            }
            try {
                LavaSprayQuenchQueue.flush(serverLevel);
            } catch (RuntimeException exception) {
                dimblend.blocks.DimBlendBlocks.LOGGER.warn("Lava spray quench flush failed", exception);
            }
        }
    }

    private NozzleLavaQuench() {
    }
}
