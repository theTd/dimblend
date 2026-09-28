package dimblend.carwash.server;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dimblend.carwash.chassis.ChassisBlocks;
import dimblend.carwash.chassis.ChassisGrimeBehaviour;
import dimblend.carwash.chassis.ChassisGrimeRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;

/**
 * 行驶积灰与雨水清洗（GAME 总线，服务端关卡 tick 后，每秒结算一次）。
 * 车厢 = Sable 子关卡，车架 BE 都在其 plot 区块里。每块车架每秒只改一次值：
 * <ul>
 * <li>车厢速度 &gt; 4 m/s：以 (速度/12)×0.5（封顶 100%，24 m/s 满概率）的概率 +1</li>
 * <li>下雨（不看遮挡）：50% 概率 −1，脏值降至 64 后雨不再洗</li>
 * </ul>
 * 同一刻集中结算，换图同步在客户端合并为每区段每秒至多一次网格重建。
 */
public final class ChassisTravelGrime {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % ChassisGrimeRules.UPDATE_INTERVAL_TICKS != 0) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        boolean raining = level.isRaining();
        RandomSource random = level.getRandom();
        for (ServerSubLevel subLevel : new ArrayList<>(container.getAllSubLevels())) {
            if (subLevel == null || subLevel.isRemoved() || subLevel.getPlot() == null) {
                continue;
            }
            double chance = ChassisGrimeRules.soilingChance(subLevel.latestLinearVelocity.length());
            if (chance > 0.0 || raining) {
                updateCar(subLevel, chance, raining, random);
            }
        }
    }

    private static void updateCar(ServerSubLevel subLevel, double soilingChance, boolean raining, RandomSource random) {
        // 改脏值只发同步包、不增删 BE，复制一份只为隔离他人在回调里改表
        for (PlotChunkHolder holder : new ArrayList<>(subLevel.getPlot().getLoadedChunks())) {
            LevelChunk chunk = holder == null ? null : holder.getChunk();
            if (chunk == null) {
                continue;
            }
            for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                if (!(be instanceof SmartBlockEntity smart) || !ChassisBlocks.isChassis(be.getBlockState())) {
                    continue;
                }
                int delta = 0;
                ChassisGrimeBehaviour grime = smart.getBehaviour(ChassisGrimeBehaviour.TYPE);
                if (raining && grime != null && grime.dirt() > ChassisGrimeRules.RAIN_WASH_FLOOR
                        && random.nextDouble() < ChassisGrimeRules.RAIN_WASH_CHANCE) {
                    delta -= ChassisGrimeRules.RAIN_WASH_AMOUNT;
                }
                if (soilingChance > 0.0 && random.nextDouble() < soilingChance) {
                    delta += 1;
                }
                if (delta > 0) {
                    ChassisGrimeBehaviour.obtain(smart).changeDirt(delta, random);
                } else if (delta < 0 && grime != null) {
                    grime.changeDirt(delta, random);
                }
            }
        }
    }

    private ChassisTravelGrime() {
    }
}
