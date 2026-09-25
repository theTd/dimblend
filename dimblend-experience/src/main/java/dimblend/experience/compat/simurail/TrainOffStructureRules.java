package dimblend.experience.compat.simurail;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.exploration.CorridorRespawnLocator;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * E7 离结构传送（服务端，仅 rotating）：
 * 同维度任意车架 |visualSpeed| &gt; 4 m/s 时，每 10 秒以玩家为中心探测半径 32 格内
 * 是否有 sable 子层级；都无则传送回重生位置（有效床/锚点，否则走廊旁，对齐 A2）。
 * 走廊条带 |z|≤16 视为安全落点，避免无床玩家被周期反复传送。
 *
 * <p>创造/旁观豁免；sable 或缺 simurail 时 fail-open（没有车架登记即无速度闸）。
 * 对 {@code Sable.HELPER} / {@link TrainBrakeBroadcast} 的引用只在守卫通过后触达，
 * JVM 惰性解析——与 {@code StructureBedGuard} / {@code SimurailBlockGuard} 同型。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class TrainOffStructureRules {

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()) {
            return;
        }
        if (player.tickCount % TrainOffStructureMath.CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        if (!Config.OFF_STRUCTURE_TELEPORT.get() || !RotatingDimension.is(player)) {
            return;
        }
        if (!player.isAlive() || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!ModList.get().isLoaded("simurail") || !ModList.get().isLoaded("sable")) {
            return;
        }
        checkAndTeleport(player);
    }

    private static void checkAndTeleport(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        boolean anyFast = TrainBrakeBroadcast.hasAnyFasterThan(
                level.dimension(), TrainOffStructureMath.SPEED_THRESHOLD, now);
        boolean inCorridor = TrainOffStructureMath.isInCorridorSafeBand(player.getZ());
        if (!anyFast || inCorridor) {
            return;
        }
        boolean nearStructure = hasNearbyStructure(player);
        if (!TrainOffStructureMath.shouldTeleport(anyFast, nearStructure, inCorridor)) {
            return;
        }
        teleportToRespawn(player);
    }

    private static boolean hasNearbyStructure(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(level, player.position());
        double r = TrainOffStructureMath.STRUCTURE_RADIUS;
        BoundingBox3d query = new BoundingBox3d(
                world.x - r, world.y - r, world.z - r,
                world.x + r, world.y + r, world.z + r);
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, query)) {
            BoundingBox3dc box = subLevel.boundingBox();
            if (TrainOffStructureMath.isWithinRadius(
                    world.x, world.y, world.z,
                    box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ(),
                    r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 活体传送到重生点：有效床/锚点且落在 rotating 内则用原版站立点；
     * 缺失/被挡/落在其他维度则改走 A2 走廊旁，绝不弹世界出生点。
     */
    private static void teleportToRespawn(ServerPlayer player) {
        DimensionTransition transition = player.findRespawnPositionAndUseSpawnBlock(
                true, DimensionTransition.DO_NOTHING);
        ServerLevel destLevel = transition.newLevel();
        Vec3 dest = transition.pos();
        float yaw = transition.yRot();
        if (transition.missingRespawnBlock() || destLevel == null || !RotatingDimension.is(destLevel)) {
            destLevel = player.server.getLevel(RotatingDimension.key());
            if (destLevel == null) {
                destLevel = player.serverLevel();
            }
            dest = CorridorRespawnLocator.findRespawnPosition(destLevel, player.getX());
            yaw = 0.0F;
        }
        player.teleportTo(destLevel, dest.x, dest.y, dest.z, yaw, 0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private TrainOffStructureRules() {
    }
}
