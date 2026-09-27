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
 * 行驶积灰与雨水清洗（GAME 总线，服务端关卡 tick 后）。车厢 = Sable 子关卡，车架 BE 都在其 plot 区块里。
 * <ul>
 * <li>每秒：车厢速度 &gt; 4 m/s 时，其每个车架各以 速度/12 的概率脏值 +1</li>
 * <li>每分钟：下雨时所有车架脏值 -16</li>
 * </ul>
 */
public final class ChassisTravelGrime {

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long time = level.getGameTime();
        boolean soilingTick = time % ChassisGrimeRules.SOILING_ROLL_INTERVAL_TICKS == 0;
        boolean rainTick = time % ChassisGrimeRules.RAIN_WASH_INTERVAL_TICKS == 0 && level.isRaining();
        if (!soilingTick && !rainTick) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        RandomSource random = level.getRandom();
        for (ServerSubLevel subLevel : new ArrayList<>(container.getAllSubLevels())) {
            if (subLevel == null || subLevel.isRemoved() || subLevel.getPlot() == null) {
                continue;
            }
            double chance = soilingTick
                    ? ChassisGrimeRules.soilingChance(subLevel.latestLinearVelocity.length())
                    : 0.0;
            if (chance > 0.0 || rainTick) {
                updateCar(subLevel, chance, rainTick, random);
            }
        }
    }

    private static void updateCar(ServerSubLevel subLevel, double soilingChance, boolean rainWash, RandomSource random) {
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
                if (soilingChance > 0.0 && random.nextDouble() < soilingChance) {
                    ChassisGrimeBehaviour.obtain(smart).changeDirt(1, random);
                }
                if (rainWash) {
                    ChassisGrimeBehaviour grime = smart.getBehaviour(ChassisGrimeBehaviour.TYPE);
                    if (grime != null) {
                        grime.changeDirt(-ChassisGrimeRules.RAIN_WASH_AMOUNT, random);
                    }
                }
            }
        }
    }

    private ChassisTravelGrime() {
    }
}
