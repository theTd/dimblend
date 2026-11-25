package dimblend.experience.compat.simurail.client;

import java.util.concurrent.CompletableFuture;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.ModSounds;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.compat.simurail.TrainBrakeSustainRules;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * 一台车架的刹车持续鸣响实例（客户端，2026-11-25 LOCKED #4）。
 *
 * <ul>
 * <li><b>为什么必须自己算世界坐标</b>：实例挂的是车架的 plot 坐标（子层级局部坐标），
 * Sable 的 {@code MovingSoundInstanceDelegate} 会包住实例并改写 x/y/z，而它的停止路径
 * 会丢掉我们自己的 {@code stop()} 语义（见 {@code report-w1.md} 的停止 bug）。所以每
 * tick 用 {@link SableWorldPosition#project} 把车架中心投到世界坐标，直接写进 {@code x/y/z}，
 * 让源随车移动而声音实例本身仍是引擎直接持有的那个对象。</li>
 * <li><b>持续鸣响</b>：{@link #getStream} 返回自建 PCM 流（75% 音节交叉淡化循环），
 * 见 {@link TrainBrakeSustainAudioStream}。</li>
 * <li><b>速度跌破 2 m/s</b>：{@link TrainBrakeSustainAudioStream.Control#releaseSustain()}
 * 放行素材自带的衰减尾音，同时 20 tick（1s）音量线性淡出，归零即 {@code stop()}。</li>
 * <li><b>松闸 / 车架消失</b>：服务端停止包直接调用 {@link #stopNow()}（5 ms 斜坡 + 引擎瞬停）。</li>
 * </ul>
 *
 * <p>速度取 Sable 客户端口径 {@code Sable.HELPER.getVelocity}（与 {@code CouplerCharge}
 * 同源）：它是子层级插值位姿的每帧位移，等价于车体速度；未在子层级内（车被拆掉/放回地面）
 * 返回 0，正好触发淡出。</p>
 */
@OnlyIn(Dist.CLIENT)
public class TrainBrakeSoundInstance extends AbstractTickableSoundInstance {

    /** 淡出步进：满幅 → 0 走满 {@link TrainBrakeSustainRules#FADE_OUT_TICKS}。 */
    private static final float FADE_STEP = 1.0F / TrainBrakeSustainRules.FADE_OUT_TICKS;

    private final ResourceKey<Level> dimension;
    private final BlockPos plotPos;
    private final TrainBrakeSustainAudioStream.Control control = new TrainBrakeSustainAudioStream.Control();
    private float baseVolume;
    private float fadeFactor = 1.0F;
    private boolean fadingOut;
    private volatile boolean pcmMissing;

    TrainBrakeSoundInstance(ResourceKey<Level> dimension, BlockPos plotPos, float volume, float pitch) {
        super(ModSounds.TRAIN_BRAKE_SUSTAIN.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.dimension = dimension;
        this.plotPos = plotPos.immutable();
        this.baseVolume = volume;
        this.volume = volume;
        this.pitch = pitch;
        // 循环由 PCM 流自己做：源端 looping 会让 OpenAL 反复播已入队的块，游标就失控了
        this.looping = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.relative = false;
        this.updatePosition();
    }

    /**
     * 真流式钩子（NeoForge 21.1.249 {@code SoundInstance#getStream}）。{@code sounds.json}
     * 里 {@code train_brake_sustain} 必须带 {@code "stream": true}，否则引擎走
     * {@code getCompleteBuffer} 静态路径、永不调用本方法。
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.<AudioStream>supplyAsync(() -> {
            TrainBrakePcmCache cache = TrainBrakePcmCache.load();
            if (cache == null) {
                this.pcmMissing = true;
                return TrainBrakeSustainAudioStream.silent(TrainBrakePcmCache.FALLBACK_FORMAT, this.control);
            }
            return cache.newStream(this.control);
        }, Util.nonCriticalIoPool());
    }

    /** 零音量也允许起播：远处不可闻时仍留在播放集合里，靠近后音量自然抬起来。 */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().equals(this.dimension)) {
            this.stopNow();
            return;
        }
        if (!blockEntityPresent(level, this.plotPos)) {
            this.stopNow();
            return;
        }
        this.updatePosition();
        if (this.pcmMissing) {
            this.stopNow();
            return;
        }
        if (!this.fadingOut && TrainBrakeSustainRules.shouldFadeOut(currentSpeed(level, this.plotPos))) {
            this.fadingOut = true;
            this.control.releaseSustain();
        }
        if (this.fadingOut) {
            this.fadeFactor = Math.max(0.0F, this.fadeFactor - FADE_STEP);
            this.volume = this.baseVolume * this.fadeFactor;
            if (this.fadeFactor <= 0.0F) {
                this.stopNow();
            }
        } else {
            this.volume = this.baseVolume;
        }
    }

    /** 松闸 / 车架消失：先给流 5 ms 归零，再让引擎立刻收声道。 */
    void stopNow() {
        this.control.stopImmediately();
        this.stop();
    }

    /** 保活重发时只刷新幅度与音调，不重起循环（已在播就是同一个游标继续走）。 */
    void refresh(float volume, float pitch) {
        if (this.isStopped()) {
            return;
        }
        this.baseVolume = volume;
        this.pitch = pitch;
        if (!this.fadingOut) {
            this.volume = volume;
        }
    }

    private void updatePosition() {
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 world = Vec3.atCenterOf(this.plotPos);
        if (level != null) {
            world = SableWorldPosition.project(level, world);
        }
        this.x = world.x;
        this.y = world.y;
        this.z = world.z;
    }

    /** 非阻塞查方块实体：客户端 {@code getChunkNow} 不会触发同步加载。 */
    private static boolean blockEntityPresent(ClientLevel level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return false;
        }
        return chunk.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey && !bogey.isRemoved();
    }

    private static double currentSpeed(Level level, BlockPos pos) {
        if (!ModList.get().isLoaded("sable")) {
            return 0.0D;
        }
        return Sable.HELPER.getVelocity(level, Vec3.atCenterOf(pos)).length();
    }
}
