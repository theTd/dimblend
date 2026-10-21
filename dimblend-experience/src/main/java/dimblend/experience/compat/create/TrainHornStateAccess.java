package dimblend.experience.compat.create;

/**
 * {@code WhistleHornBlockEntityMixin} 的 @Unique 锁定字段访问口——供
 * {@link TrainHorn} 触发路径读写汽笛的「鸣笛锁定到第几 tick」服务端状态，
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
}
