package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.fluids.pipes.valve.FluidValveVisual;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 流体阀门 Flywheel 视觉刷新（LOCKED DECISION #12 {@code valve_snap_revert} 的客户端一半）。
 *
 * <p>{@code FluidValveVisual#beginFrame} 首行是 {@code pointer.settled() && this.settled}
 * 的懒更新短路：只要上一帧算过且指针"已到位"，就不再调 {@code transformPointer}。
 * 但指针值可能在<b>帧外</b>被数据包改写（{@code LerpedFloat.readNBT} 带 Force 时直接
 * {@code startWithValue}），此时 {@code pointer.settled()} 依旧为 true、{@code settled}
 * 为 true，于是一直画旧角度——摇杆停下后阀门仍在"半开"位置。</p>
 *
 * <p>修法：{@code beginFrame} HEAD 把 {@code settled} 复位为 false，短路失效，
 * 本帧立刻走 {@code transformPointer} 重算（与原版 {@code update(float)} 等价，
 * 但该类并没有声明 {@code update}，它继承自基类的动态视觉，故注在 {@code beginFrame}）。
 * 每帧多算一次矩阵的代价可接受（阀门实例极少）。</p>
 */
@Mixin(FluidValveVisual.class)
public abstract class FluidValveVisualMixin {

    @Shadow(remap = false)
    protected boolean settled;

    @Inject(method = "beginFrame", at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private void dimblend$refreshPointerEveryFrame(CallbackInfo ci) {
        this.settled = false;
    }
}
