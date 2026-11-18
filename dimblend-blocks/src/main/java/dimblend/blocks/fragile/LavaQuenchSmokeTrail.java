package dimblend.blocks.fragile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dimblend.blocks.DimBlendBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 当右键的岩浆流源头超出 16 格时，从右键位置沿上游路径至源头逐 tick 依次播放
 * 烟雾粒子效果（{@link ParticleTypes#LARGE_SMOKE}）与火焰熄灭音效（{@link SoundEvents#FIRE_EXTINGUISH}），
 * 并把最先 {@link WaterBucketLavaQuenchMath#OVER_16_CUT_LIMIT} 格纯岩浆流删除，紧急截断仍在蔓延的岩浆。
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class LavaQuenchSmokeTrail {

    private static final int PARTICLES_PER_STEP = 5;
    private static final List<ActiveTrail> ACTIVE_TRAILS = new ArrayList<>();

    private static final class ActiveTrail {
        final ResourceKey<Level> dimension;
        final List<BlockPos> path;
        final int deleteFirstCount;
        int nextIndex;

        ActiveTrail(ResourceKey<Level> dimension, List<BlockPos> path, int deleteFirstCount, int nextIndex) {
            this.dimension = dimension;
            this.path = path;
            this.deleteFirstCount = deleteFirstCount;
            this.nextIndex = nextIndex;
        }
    }

    /**
     * 登记一条从右键格到源头的烟雾回溯轨迹：首格当场播放，后续格每服务端 tick 推进一格。
     *
     * @param deleteFirstCount 前多少格纯岩浆流在效果播放后被删除（生产路径为
     *                         {@link WaterBucketLavaQuenchMath#OVER_16_CUT_LIMIT}）
     */
    public static void schedule(ServerLevel level, List<BlockPos> pathFromClickedToSource, int deleteFirstCount) {
        if (pathFromClickedToSource.isEmpty()) {
            return;
        }
        List<BlockPos> copy = List.copyOf(pathFromClickedToSource);
        int limit = Math.max(0, deleteFirstCount);
        emitStep(level, copy.get(0), 0, limit);
        if (copy.size() > 1) {
            ACTIVE_TRAILS.add(new ActiveTrail(level.dimension(), copy, limit, 1));
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (ACTIVE_TRAILS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        Iterator<ActiveTrail> it = ACTIVE_TRAILS.iterator();
        while (it.hasNext()) {
            ActiveTrail trail = it.next();
            ServerLevel level = server.getLevel(trail.dimension);
            if (level == null || trail.nextIndex >= trail.path.size()) {
                it.remove();
                continue;
            }
            BlockPos pos = trail.path.get(trail.nextIndex);
            emitStep(level, pos, trail.nextIndex, trail.deleteFirstCount);
            trail.nextIndex++;
            if (trail.nextIndex >= trail.path.size()) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ACTIVE_TRAILS.clear();
    }

    /** 供 GameTest 读取当前仍在推进的轨迹数量。 */
    public static int activeTrailCount() {
        return ACTIVE_TRAILS.size();
    }

    /** 供 GameTest 清空残留轨迹，保证用例隔离。 */
    public static void clearActiveTrails() {
        ACTIVE_TRAILS.clear();
    }

    private static void emitStep(ServerLevel level, BlockPos pos, int stepIndex, int deleteFirstCount) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return;
        }
        RandomSource random = level.getRandom();
        float pitch = 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F;
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, pitch);
        level.sendParticles(
                ParticleTypes.LARGE_SMOKE,
                pos.getX() + 0.5D,
                pos.getY() + 0.65D,
                pos.getZ() + 0.5D,
                PARTICLES_PER_STEP,
                0.25D,
                0.2D,
                0.25D,
                0.01D
        );
        // 效果先播再删；只删纯岩浆流，源头与更远的岩浆流保留。
        boolean lavaCell = WaterBucketLavaQuench.isPureLavaCell(level, pos);
        boolean source = lavaCell && level.getFluidState(pos).isSource();
        if (WaterBucketLavaQuenchMath.shouldDeleteOnOver16Trail(
                stepIndex, deleteFirstCount, lavaCell, source)) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    private LavaQuenchSmokeTrail() {
    }
}
