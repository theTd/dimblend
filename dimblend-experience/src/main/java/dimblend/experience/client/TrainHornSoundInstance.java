package dimblend.experience.client;

import dimblend.experience.compat.create.TrainHorn;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 列车汽笛一次性客户端声音实例（需求口径 2026-10-21）：
 *
 * <ul>
 * <li><b>距离曲线</b>：{@code Attenuation.NONE} 关闭 OpenAL 距离衰减（
 * {@code Channel.disableAttenuation} 把距离模型设为 AL_NONE），每 tick 由本类手算
 * 音量：256 格内全音量，256→512 线性衰减至零，512 以外静音——原版 LINEAR 从 0 格
 * 就开始衰减，表达不了「256 才开始」；音量字段被引擎钳到 [0,1]
 * （{@code SoundEngine.calculateVolume}），曲线值域本身不超 1，无钳制失真。</li>
 * <li><b>方向性（立体声）</b>：非 relative 的世界坐标声源 + 单声道素材，OpenAL 按
 * 听者朝向实时声像（立体声素材不做空间化，素材已转单声道）。无视遮挡（原版无遮挡机制）。</li>
 * <li><b>Sable</b>：存 plot 坐标，每 tick 经 {@link SableWorldPosition} 投影到结构当前
 * 世界位置——声源随移动中的结构走。</li>
 * <li><b>生命周期</b>：非循环素材播完由引擎收通道；另加 duration+10 tick 兜底自停。</li>
 * </ul>
 */
public class TrainHornSoundInstance extends AbstractTickableSoundInstance {

    private final BlockPos plotPos;
    private final int maxAge;
    private int age;

    public TrainHornSoundInstance(SoundEvent event, BlockPos plotPos, float pitch, int durationTicks) {
        super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.plotPos = plotPos;
        this.pitch = pitch;
        this.looping = false;
        this.attenuation = Attenuation.NONE;
        this.relative = false;
        this.maxAge = durationTicks + 10;
        Vec3 world = projectPosition();
        this.x = world.x;
        this.y = world.y;
        this.z = world.z;
        this.volume = volumeAt(world);
    }

    @Override
    public void tick() {
        Vec3 world = projectPosition();
        this.x = world.x;
        this.y = world.y;
        this.z = world.z;
        this.volume = volumeAt(world);
        if (++this.age > this.maxAge) {
            this.stop();
        }
    }

    private Vec3 projectPosition() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return Vec3.atCenterOf(plotPos);
        }
        return SableWorldPosition.project(level, Vec3.atCenterOf(plotPos));
    }

    /** 距离曲线：256 格内 1.0，256→512 线性到 0，512 以外 0。 */
    private static float volumeAt(Vec3 world) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer.getMainCamera().getEntity() == null) {
            return 0.0F;
        }
        double distance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(world);
        if (distance <= TrainHorn.FLAT_RADIUS) {
            return 1.0F;
        }
        if (distance >= TrainHorn.MAX_RADIUS) {
            return 0.0F;
        }
        return (float) ((TrainHorn.MAX_RADIUS - distance) / (TrainHorn.MAX_RADIUS - TrainHorn.FLAT_RADIUS));
    }
}
