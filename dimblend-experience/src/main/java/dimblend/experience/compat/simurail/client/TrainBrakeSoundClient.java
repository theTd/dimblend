package dimblend.experience.compat.simurail.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dimblend.experience.ModSounds;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.compat.simurail.TrainBrakeSoundPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
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
 *
 * <p>{@link #playGlobal} 是另一条独立链路：施闸/末影刹车的一次性 {@code train_brake} 播报
 * （{@code GlobalTrainBrakeSoundPayload}），与上面按车架登记表管理的持续鸣响互不相干。</p>
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

    /**
     * 全图一次性播报（末影刹车 / 常规施闸）：{@link dimblend.experience.compat.simurail.GlobalTrainBrakeSoundPayload}
     * 的落地处（2026-11-27 Slice 10）。
     *
     * <p>实例建在原点、{@code relative = true}、{@code Attenuation.NONE}，与
     * {@code ChaseMusicSoundInstance} 同一套口径：</p>
     * <ul>
     * <li>{@code relative} + 原点：绑定相机而非世界坐标。列车 15–32 m/s 前进、玩家坐在 Sable
     * 座椅上时 {@code player.getX()/Y()/Z()} 落在 {@code ~1.6×10^7} 的 plot 空间，任何
     * 「以某个世界坐标当源」的播报都会当场偏离 16 格线性衰减区。</li>
     * <li>{@code Attenuation.NONE}：{@code SoundEngine#play} 走 {@code disableAttenuation()}，
     * 不随距离掉音量，{@code pitch = 0.5} 的 10 秒末影播报能完整听完。</li>
     * <li>原点还顺带避开 Sable {@code SoundEngineMixin#sable$play}：它对
     * {@code getContaining(level, x, z)} 命中的实例会包成 {@code MovingSoundInstanceDelegate}，
     * 而 plot 坐标 {@code ~2×10^7} 才会命中；原点不在任何子层级里。</li>
     * <li>{@link SoundSource#BLOCKS}：跟随玩家的方块音量条，而非 MUSIC。</li>
     * </ul>
     *
     * <p>种子由服务端统一给出（{@code level.getRandom().nextLong()}），因此每个客户端拿到的
     * 变体/音量抖动都一致，不会出现「有人听见 A、有人听见 B」。</p>
     */
    public static void playGlobal(float volume, float pitch, long seed) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        SoundInstance sound = new SimpleSoundInstance(
                ModSounds.TRAIN_BRAKE.get().getLocation(),
                SoundSource.BLOCKS,
                volume,
                pitch,
                RandomSource.create(seed),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0D,
                0.0D,
                0.0D,
                true);
        minecraft.getSoundManager().play(sound);
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
