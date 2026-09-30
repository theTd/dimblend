package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Create 应力网络"未加载份额"账本的读写入口（{@code KineticNetwork} 三个 private 字段，
 * 6.0.10-281 字节码）：{@code initFromTE} 用首个初始化成员的存档总量写入，
 * 成员首 tick {@code addSilently} 从中扣掉自己的份额；{@code add/remove} 不碰它。
 * 调用方见 {@code dimblend.experience.compat.create.KineticUnloadedShare}。
 */
@Mixin(KineticNetwork.class)
public interface KineticNetworkUnloadedAccessor {

    @Accessor("unloadedCapacity")
    float dimblend$unloadedCapacity();

    @Accessor("unloadedCapacity")
    void dimblend$setUnloadedCapacity(float value);

    @Accessor("unloadedStress")
    float dimblend$unloadedStress();

    @Accessor("unloadedStress")
    void dimblend$setUnloadedStress(float value);

    @Accessor("unloadedMembers")
    int dimblend$unloadedMembers();

    @Accessor("unloadedMembers")
    void dimblend$setUnloadedMembers(int value);
}
