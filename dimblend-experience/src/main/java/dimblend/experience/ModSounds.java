package dimblend.experience;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 全部自定义音效事件（E2-E4 列车音效 + D4 马达音效）。
 * 注册无条件：SoundEvent 本身对缺失的模组无副作用，
 * 播放侧由 mixin 插件的条件加载与 Config 开关双重门控。
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, DimBlend.MODID);

    /** E2：转向架行进声（每秒一条脉冲，速度映射音量/音调）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BOGEY_TRACK_LOOP =
            register("bogey_track_loop");
    /** E3：施加刹车（刹车强度 0→正 沿）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> TRAIN_BRAKE =
            register("train_brake");
    /** E4：松闸泄气（刹车强度 正→0 沿）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> BRAKE_RELEASE =
            register("brake_release");
    /** D4：电动马达运转循环（|面板|>64rpm 且运转时）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ELECTRIC_MOTOR_LOOP =
            register("electric_motor_loop");
    /** D4：电动马达启动（进入运转态单次播放，与循环同时起、盖住循环头）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ELECTRIC_MOTOR_STARTUP =
            register("electric_motor_startup");
    /** D4：电动马达停转（离开运转态单次播放）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> ELECTRIC_MOTOR_STOPPING =
            register("electric_motor_stopping");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () ->
                SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, name)));
    }

    private ModSounds() {
    }
}
