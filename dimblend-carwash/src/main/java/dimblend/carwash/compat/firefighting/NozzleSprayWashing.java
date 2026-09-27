package dimblend.carwash.compat.firefighting;

import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayBlockInteraction;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayFluidType;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayHitContext;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayInteractionRegistry;
import dimblend.carwash.chassis.ChassisBlocks;
import dimblend.carwash.server.ChassisWashing;
import net.minecraft.server.level.ServerLevel;

/**
 * Create: FireFighting Additions 喷淋命中车架：水喷淋视同一次清洗（冷却 0.25 秒，见
 * {@link ChassisWashing#sprayWash}）。固定喷头、手持喷枪、装在移动结构上的喷头都走同一接口；
 * 车厢里的车架经 Sable 的射线投影命中，{@code pos} 为 plot 坐标，只用 pos/state/流体类型。
 *
 * <p>仅在该 mod 在场时由主类注册（本类实现其接口）。</p>
 */
public final class NozzleSprayWashing implements NozzleSprayBlockInteraction {

    public static void register() {
        NozzleSprayInteractionRegistry.register(new NozzleSprayWashing());
    }

    @Override
    public boolean shouldReceive(NozzleSprayHitContext context) {
        return context.fluidType() == NozzleSprayFluidType.WATER
                && context.level() instanceof ServerLevel
                && ChassisBlocks.isChassis(context.state());
    }

    @Override
    public void onHit(NozzleSprayHitContext context) {
        if (context.level() instanceof ServerLevel level) {
            ChassisWashing.sprayWash(level, context.pos());
        }
    }

    private NozzleSprayWashing() {
    }
}
