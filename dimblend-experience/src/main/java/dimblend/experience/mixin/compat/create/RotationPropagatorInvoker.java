package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * F2 连通扫描入口：复用 Create 自己的候选邻居位置枚举
 * （{@code RotationPropagator#getPotentialNeighbourLocations}，private static；
 * 6.0.10-281 字节码：六向相邻且已加载 + {@code KineticBlockEntity#addPropagationLocations}
 * 的大齿轮斜向/链传动等附加位置），不在本模组重抄一份候选规则。
 * 调用方见 {@code dimblend.experience.compat.create.KineticComponentScan}。
 */
@Mixin(RotationPropagator.class)
public interface RotationPropagatorInvoker {

    @Invoker(value = "getPotentialNeighbourLocations", remap = false)
    static List<BlockPos> dimblend$getPotentialNeighbourLocations(KineticBlockEntity be) {
        throw new AssertionError("RotationPropagatorInvoker mixin not applied");
    }
}
