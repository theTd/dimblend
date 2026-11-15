package dimblend.carwash.client;

import dimblend.carwash.DimBlendCarwash;
import dimblend.carwash.chassis.ChassisBlocks;
import dimblend.carwash.chassis.ChassisGrimeBehaviour;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 创造模式伪装半砖洗净后的向下滴水特效（纯客户端）。
 *
 * <p>触发链：服务端 {@link ChassisGrimeBehaviour#changeDirt} 把半砖脏值降下来时序号 +1 并
 * {@code sendData()}；客户端行为读同步包时发现序号变化，回调到这里记下该 plot 坐标
 * （Sable 车厢在客户端同样活在 plot 坐标上，BE 的 {@link BlockPos} 直接就是粒子位置）。</p>
 *
 * <p>上半砖的碰撞盒为 {@code [0.5, 1.0]}，而原版 {@code DripParticle} 碰撞盒高度为 {@code 0.01}
 * （中心在 {@code y + 0.005}），Sable 的 {@code ParticleMixin} 每刻从包围盒中心做碰撞检测；
 * 因此生成高度取 {@code y + 0.49}（顶边紧贴 {@code y + 0.5} 半砖底面且不进入上半砖碰撞盒），
 * 双层半砖则沿暴露侧面外侧 {@code 0.02} 格的同一半高线向下滴落。</p>
 */
@EventBusSubscriber(modid = DimBlendCarwash.MODID, value = Dist.CLIENT)
public final class CreativeSlabDripClient {

    /** 紧贴半砖底面（0.5）正下方，避开 DripParticle 0.01 高度与上半砖 [0.5, 1.0] 碰撞盒相交。 */
    public static final double HALF_HEIGHT_DRIP_Y = 0.49D;

    /** 一次洗净后的滴水时长：5 秒。 */
    private static final int DRIP_DURATION_TICKS = 100;
    /** 同时跟踪的半砖上限：整列车淋雨/水桶洗时不至于每刻跑几千个粒子。 */
    private static final int MAX_TRACKED_SLABS = 512;
    /** 每刻每块半砖的出滴概率。 */
    private static final float DRIP_CHANCE = 0.35F;

    private static final Direction[] HORIZONTALS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private static final Map<BlockPos, Long> ACTIVE_SLABS = new HashMap<>();

    /** 当前跟踪表所属的客户端世界；换世界即整表作废。 */
    private static ClientLevel trackedLevel;

    /** MOD 装配时由 {@link DimBlendCarwash} 在客户端分支调用：接上 BE 的洗净回调。 */
    public static void init() {
        ChassisGrimeBehaviour.setClientWashDripCallback(CreativeSlabDripClient::onWashDrip);
    }

    private static void onWashDrip(Level level, BlockPos pos) {
        if (!(level instanceof ClientLevel clientLevel)) {
            return;
        }
        if (Minecraft.getInstance().level != clientLevel) {
            return;
        }
        if (trackedLevel != clientLevel) {
            ACTIVE_SLABS.clear();
            trackedLevel = clientLevel;
        }
        if (!ACTIVE_SLABS.containsKey(pos) && ACTIVE_SLABS.size() >= MAX_TRACKED_SLABS) {
            return;
        }
        ACTIVE_SLABS.put(pos, clientLevel.getGameTime() + DRIP_DURATION_TICKS);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            if (!ACTIVE_SLABS.isEmpty()) {
                ACTIVE_SLABS.clear();
            }
            trackedLevel = null;
            return;
        }
        if (trackedLevel != level) {
            ACTIVE_SLABS.clear();
            trackedLevel = level;
            return;
        }
        // 暂停时 gameTime 冻结但 ClientTickEvent 照发：此时不刷粒子，免得同一刻叠一堆
        if (mc.isPaused() || ACTIVE_SLABS.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        RandomSource random = level.getRandom();
        for (Iterator<Map.Entry<BlockPos, Long>> it = ACTIVE_SLABS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> entry = it.next();
            BlockPos pos = entry.getKey();
            if (gameTime >= entry.getValue() || !level.isLoaded(pos)) {
                it.remove();
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!ChassisBlocks.isCreativeSlab(state)) {
                it.remove();
                continue;
            }
            if (random.nextFloat() < DRIP_CHANCE) {
                spawnDripParticle(level, pos, state, random);
            }
        }
    }

    private static void spawnDripParticle(
            ClientLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (ChassisBlocks.isCreativeTopHalfSlab(state)) {
            level.addParticle(
                    ParticleTypes.FALLING_WATER,
                    pos.getX() + 0.1D + 0.8D * random.nextDouble(),
                    pos.getY() + HALF_HEIGHT_DRIP_Y,
                    pos.getZ() + 0.1D + 0.8D * random.nextDouble(),
                    0.0D,
                    0.0D,
                    0.0D);
            return;
        }
        int start = random.nextInt(HORIZONTALS.length);
        for (int i = 0; i < HORIZONTALS.length; i++) {
            Direction side = HORIZONTALS[(start + i) % HORIZONTALS.length];
            BlockPos adj = pos.relative(side);
            if (!level.isLoaded(adj) || !level.getBlockState(adj).getCollisionShape(level, adj).isEmpty()) {
                continue;
            }
            double along = 0.1D + 0.8D * random.nextDouble();
            double x = switch (side) {
                case WEST -> pos.getX() - 0.02D;
                case EAST -> pos.getX() + 1.02D;
                default -> pos.getX() + along;
            };
            double z = switch (side) {
                case NORTH -> pos.getZ() - 0.02D;
                case SOUTH -> pos.getZ() + 1.02D;
                default -> pos.getZ() + along;
            };
            level.addParticle(
                    ParticleTypes.FALLING_WATER, x, pos.getY() + HALF_HEIGHT_DRIP_Y, z, 0.0D, 0.0D, 0.0D);
            return;
        }
    }

    /** 客户端世界卸载：plot 坐标在新世界里没有意义，整表清空。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            ACTIVE_SLABS.clear();
            trackedLevel = null;
        }
    }

    private CreativeSlabDripClient() {
    }
}
