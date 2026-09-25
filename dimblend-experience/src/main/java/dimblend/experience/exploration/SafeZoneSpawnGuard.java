package dimblend.experience.exploration;

import java.util.Set;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * A6 安全区生成拦截：|z|≤64 内仅拦自然类生成（自然游荡、区块生成、结构、
 * 巡逻队、增援）；刷怪笼、刷怪蛋、发射器、繁殖、命令等玩家侧与机器间接生成
 * 一律放行（口径经用户确认）。
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class SafeZoneSpawnGuard {

    public static final int SAFE_RADIUS = 64;

    /** 按用户口径定义的“自然类”生成来源。 */
    private static final Set<MobSpawnType> NATURAL_TYPES = Set.of(
            MobSpawnType.NATURAL,
            MobSpawnType.CHUNK_GENERATION,
            MobSpawnType.STRUCTURE,
            MobSpawnType.PATROL,
            MobSpawnType.REINFORCEMENT);

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!Config.SAFE_ZONE.get() || !NATURAL_TYPES.contains(event.getSpawnType())) {
            return;
        }
        ServerLevel level = resolveServerLevel(event.getLevel());
        if (level == null || !RotatingDimension.is(level)) {
            return;
        }
        if (Math.abs(event.getZ()) <= SAFE_RADIUS) {
            // 双保险：cancel 跳过 finalize（连带生成如鸡骑士母鸡也不会出现），
            // spawnCancelled 阻止实体进入世界
            event.setCanceled(true);
            event.setSpawnCancelled(true);
        }
    }

    /**
     * worldgen 阶段（区块生成/结构体）事件的 level 载体是 WorldGenRegion，
     * 需解包出真正的 ServerLevel 才能判维度；未知载体 fail-open 放行。
     */
    private static ServerLevel resolveServerLevel(LevelAccessor level) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        if (level instanceof WorldGenRegion region) {
            return region.getLevel();
        }
        return null;
    }

    private SafeZoneSpawnGuard() {
    }
}
