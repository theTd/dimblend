package dimblend.experience.mixin.compat.coldsweat;

import com.momosoftworks.coldsweat.common.blockentity.ThermolithBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ThermolithBlockEntity#signal} 的读写口（Cold Sweat 2.4.2 里是 private int，
 * 只有 {@code getSignal()} 读取器）。模式重算要直接覆盖它——原版 {@code getSignal()}
 * 只在朝向背面输出，{@code getDirectSignal()} 走 {@code BlockState#getSignal} 同一条路径，
 * 所以写字段就够了，不需要另外动方块态。
 *
 * <p>写成接口 mixin 而不是在类 mixin 里加 public 方法：本仓库 Mixin 纪律只允许类 mixin
 * 出现 private 成员，{@code @Accessor} 接口是官方豁免形态。</p>
 */
@Mixin(ThermolithBlockEntity.class)
public interface ThermolithBlockEntityAccessor {

    @Accessor("signal")
    int dimblend$getSignal();

    @Accessor("signal")
    void dimblend$setSignal(int signal);
}
