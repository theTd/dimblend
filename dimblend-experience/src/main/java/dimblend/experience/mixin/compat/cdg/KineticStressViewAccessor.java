package dimblend.experience.mixin.compat.cdg;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * B6 重建稳定探测的只读视图入口：{@code KineticBlockEntity} 的
 * {@code stress}/{@code networkSize} 分别是 protected/private、无公开 getter，
 * 而网络视图又不能用 {@code getOrCreateNetwork()} 读（有创建副作用）。
 * 目标虽是 Create 类，但仅服务 CDG 柴油机逻辑，故按 createdieselgenerators
 * 在场过滤（与 ElectricMotorGeneratorStatsMixin 按 createaddition 过滤同判例）。
 * 两个 accessor 均只读，不引入任何 Create 侧状态变更。
 */
@Mixin(KineticBlockEntity.class)
public interface KineticStressViewAccessor {

    @Accessor("stress")
    float dimblend$stress();

    @Accessor("networkSize")
    int dimblend$networkSize();
}
