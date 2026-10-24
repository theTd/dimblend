package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlockEntity;
import dimblend.experience.compat.create.TrainHornStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 列车汽笛服务端锁定状态（持续高电平不重触发，断电立即清零）：纯内存字段不落盘，
 * 区块卸载/重进清零（同 {@code SteamEngineOverloadMixin} 口径）。读写走
 * {@link TrainHornStateAccess}，mixin 包外不引用本类。
 */
@Mixin(WhistleBlockEntity.class)
public abstract class WhistleHornBlockEntityMixin implements TrainHornStateAccess {

    /** 鸣笛锁定截止 tick（服务端 gameTime），0 = 未锁定。 */
    @Unique
    private long dimblend$hornUntilTick;

    /** 当前/最近一次锁定的音效种类序号（{@code TrainHorn.HornKind#ordinal()}），-1 = 从未触发。 */
    @Unique
    private int dimblend$hornKind = -1;

    @Override
    public long dimblend$hornUntilTick() {
        return dimblend$hornUntilTick;
    }

    @Override
    public int dimblend$hornKind() {
        return dimblend$hornKind;
    }

    @Override
    public void dimblend$setHornLock(long untilTick, int hornKind) {
        this.dimblend$hornUntilTick = untilTick;
        this.dimblend$hornKind = hornKind;
    }
}
