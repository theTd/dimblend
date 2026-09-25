package dimblend.experience.client;

import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * D4 电动马达运转音（纯客户端）：静态世界坐标定点<b>单次</b>播放，
 * 由 {@code ElectricMotorSoundClientMixin} 状态机运转期间每 5 tick 起一条
 * （间隔与素材时长无关，多条叠播；代码无淡入淡出，素材不裁剪）。
 *
 * <p>持有马达 BE 引用并在 tick() 内自停：马达被打掉或客户端区块卸载时
 * BE 停止 tick、状态机不再运行，若无自停则在播的运转音会在 SoundEngine 中
 * 残留成幽灵声（与 {@link BogeyTrackSound} 的 isRemoved 自停同构）。</p>
 *
 * <p>播放范围与音量（2026-09-23 用户要求）：线性衰减（{@code LINEAR}）+
 * sounds.json {@code attenuation_distance=16} 约束可听范围约 16 格，
 * 实例音量 0.5（减半）。基类默认即 LINEAR，此处显式指定作防回归/自文档。</p>
 */
public class MotorLoopSound extends AbstractTickableSoundInstance {

    /** 运转音音量：满幅减半（2026-09-23 用户要求）。 */
    public static final float LOOP_VOLUME = 0.5F;

    private final ElectricMotorBlockEntity motor;

    public MotorLoopSound(SoundEvent event, ElectricMotorBlockEntity motor) {
        super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.motor = motor;
        BlockPos pos = motor.getBlockPos();
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        this.looping = false;
        this.volume = LOOP_VOLUME;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
    }

    @Override
    public void tick() {
        if (motor.isRemoved()) {
            this.stop();
        }
    }

    /** 1.21.1 AbstractTickableSoundInstance.stop() 为 protected，对外暴露停止入口。 */
    public void dimblend$stop() {
        this.stop();
    }
}
