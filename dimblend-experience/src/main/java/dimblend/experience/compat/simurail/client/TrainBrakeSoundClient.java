package dimblend.experience.compat.simurail.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.compat.simurail.TrainBrakeSoundPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 刹车持续鸣响的客户端登记表：{@link TrainBrakeSoundPayload} 的落地处。
 *
 * <p>一个受控车架一条 {@link TrainBrakeSoundInstance}，键是（维度，plot 坐标）。
 * 开始包幂等（已在播只刷新音量/音调，游标照走），停止包立刻 {@code stopNow()}。
 * 服务端每 40 tick 保活重发一次开始包，所以中途进服、丢包、临时超出 256 格被丢掉的
 * 车架都会在下一轮补上；反过来，{@link #reset()}（登出/换档）清空全部实例与 PCM 缓存。</p>
 *
 * <p>{@link #accept} 先判维度：别的维度的包一律忽略，避免同名 plot 坐标串档。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TrainBrakeSoundClient {

    /** 超此距离不再为该车架起播（服务端的发包半径同值）；音量曲线本就在 32 格就衰减到底。 */
    private static final double MAX_AUDIBLE_RADIUS = 256.0D;

    private record Key(ResourceLocation dimension, BlockPos pos) {
    }

    private static final Map<Key, TrainBrakeSoundInstance> ACTIVE = new HashMap<>();

    /** 客户端线程落地（网络包的 {@code enqueueWork} 内）。 */
    public static void accept(TrainBrakeSoundPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        ResourceLocation current = level.dimension().location();
        if (!payload.dimension().equals(current)) {
            return;
        }
        sweepStopped();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        for (BlockPos raw : payload.bogeyPositions()) {
            BlockPos pos = raw.immutable();
            Key key = new Key(current, pos);
            if (!payload.start()) {
                TrainBrakeSoundInstance sound = ACTIVE.remove(key);
                if (sound != null) {
                    sound.stopNow();
                }
                continue;
            }
            TrainBrakeSoundInstance existing = ACTIVE.get(key);
            if (existing != null && !existing.isStopped()) {
                existing.refresh(payload.volume(), payload.pitch());
                continue;
            }
            Vec3 world = SableWorldPosition.project(level, Vec3.atCenterOf(pos));
            if (camera.distanceTo(world) > MAX_AUDIBLE_RADIUS) {
                continue;
            }
            TrainBrakeSoundInstance sound =
                    new TrainBrakeSoundInstance(level.dimension(), pos, payload.volume(), payload.pitch());
            ACTIVE.put(key, sound);
            minecraft.getSoundManager().play(sound);
        }
    }

    /** 登出 / 换档复位：停掉全部实例并丢弃 PCM 缓存（下次播放重新解码）。 */
    public static void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        for (TrainBrakeSoundInstance sound : ACTIVE.values()) {
            sound.stopNow();
            minecraft.getSoundManager().stop(sound);
        }
        ACTIVE.clear();
        TrainBrakePcmCache.clear();
    }

    private static void sweepStopped() {
        Iterator<Map.Entry<Key, TrainBrakeSoundInstance>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isStopped()) {
                iterator.remove();
            }
        }
    }

    private TrainBrakeSoundClient() {
    }
}
