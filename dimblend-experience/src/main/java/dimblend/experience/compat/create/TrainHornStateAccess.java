package dimblend.experience.compat.create;

/**
 * {@code WhistleHornBlockEntityMixin} 的 @Unique 状态访问口——供
 * {@link TrainHorn} 触发/扳手/护目镜路径读写汽笛 BE 的鸣笛锁定与音高状态，
 * 避免 mixin 包外直接引用 mixin 类（同 {@code AlternatorOverstressLatchAccess} 先例）。
 */
public interface TrainHornStateAccess {

    /** 鸣笛锁定截止 tick（服务端 gameTime；当前时刻小于该值视为播放中，新上升沿无视）。 */
    long dimblend$hornUntilTick();

    /**
     * 当前/最近一次锁定的 {@link TrainHorn.HornKind} 序号，-1 = 从未触发。
     * 锁定过期后保留旧值（有效性一律以 untilTick 为准），仅供 GameTest 断言识别结果。
     */
    int dimblend$hornKind();

    void dimblend$setHornLock(long untilTick, int hornKind);

    /** 最近一次触发下发到 {@code TrainHornPayload} 的音高（停播包不覆盖；仅供 GameTest 断言）。 */
    float dimblend$hornPitch();

    void dimblend$setHornPitch(float pitch);

    /** 本支汽笛自己的音高刻度（0..20，v = tenths / 10；随 BE NBT 落盘并同步客户端）。 */
    int dimblend$pitchTenths();

    void dimblend$setPitchTenths(int tenths);

    /**
     * 持久化的「当前处于特殊模式」标志（六面邻接引擎即真，与红石供电无关）。
     * 进入特殊模式的那一次转换把音高重置为 {@link TrainHorn#PITCH_TENTHS_DEFAULT}。
     */
    boolean dimblend$specialMode();

    void dimblend$setSpecialMode(boolean special);
}
