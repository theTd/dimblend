package dimblend.experience.mixin.compat.simulated;

import java.util.List;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import dimblend.experience.compat.simulated.VelocitySensorRelativeSpeed;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 速度传感器显示连接器新增"相对速度 km/h / mph"两个选项（2026-11-26 slice-1 #1A）。
 *
 * <p>目标类是 {@code simulated} 的
 * {@code dev.simulated_team.simulated.content.display_sources.VelocitySensorDisplaySource}
 * （运行 jar {@code simulated-neoforge-1.21.1-1.3.0.jar}，取自
 * {@code create-aeronautics-bundled-1.21.1-1.3.0.jar} 的 {@code META-INF/jarjar}）。
 * {@code javap -p -c} 实测的两个成员：</p>
 * <ul>
 *   <li>{@code java.util.List<net.minecraft.network.chat.Component> getOptions()}（包私有，单 {@code areturn}）——
 *       追加两项，index 0 的 m/s 选项原样保留；</li>
 *   <li>{@code protected MutableComponent provideLine(DisplayLinkContext, DisplayTargetStats)}（单方法，
 *       偏移 43 处 {@code getInt("VeclotySensorSelection")}，{@code ifne} → 非 0 返回 {@code ZERO}）——
 *       在 HEAD 拦下 index 1/2 自行出线，其余模式原样交给上游。</li>
 * </ul>
 *
 * <p>为什么不注册独立 {@code DisplaySource}：模拟作者的设计是"一个来源 + 模式下拉框"，
 * 选项就落在这个下拉框里；新加独立来源会另起一个列表项（来源名是"速度传感器数据"），
 * 与需求"添加选项"和既有存档的 {@code sourceConfig} 语义都不一致。走 {@code @Pseudo}
 * mixin 不引用任何 {@code dev.simulated_team.simulated.*} 类型，编译期零依赖。</p>
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.display_sources.VelocitySensorDisplaySource", remap = false)
public abstract class VelocitySensorRelativeSpeedMixin {

    @Inject(
            method = "getOptions()Ljava/util/List;",
            at = @At("RETURN"),
            require = 1,
            allow = 1,
            cancellable = true,
            remap = false
    )
    private void dimblend$appendRelativeSpeedOptions(CallbackInfoReturnable<List<Component>> cir) {
        cir.setReturnValue(VelocitySensorRelativeSpeed.withRelativeSpeedOptions(cir.getReturnValue()));
    }

    @Inject(
            method = "provideLine(Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkContext;"
                    + "Lcom/simibubi/create/content/redstone/displayLink/target/DisplayTargetStats;)"
                    + "Lnet/minecraft/network/chat/MutableComponent;",
            at = @At("HEAD"),
            require = 1,
            allow = 1,
            cancellable = true,
            remap = false
    )
    private void dimblend$relativeSpeedLine(DisplayLinkContext context, DisplayTargetStats stats,
            CallbackInfoReturnable<MutableComponent> cir) {
        int mode = context.sourceConfig().getInt(VelocitySensorRelativeSpeed.SELECTION_KEY);
        if (!VelocitySensorRelativeSpeed.isRelativeSpeedMode(mode)) {
            return;
        }
        cir.setReturnValue(VelocitySensorRelativeSpeed.line(mode,
                VelocitySensorRelativeSpeed.adjustedVelocity(context.getSourceBlockEntity())));
    }
}
