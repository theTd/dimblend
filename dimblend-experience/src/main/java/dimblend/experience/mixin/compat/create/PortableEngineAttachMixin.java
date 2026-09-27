package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dimblend.experience.compat.simulated.PortableEngineExclusivity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F2 便携引擎整网唯一的唯一触发点：{@code RotationPropagator.handleAdded} 是 Create
 * 一切“动力块（重新）接入”的收敛点（6.0.10-281 字节码核实调用方只有
 * {@code KineticBlockEntity#attachKinetics} 与 {@code GearshiftBlock#tick}）：
 * <ul>
 * <li>新 BE 首 tick（构造即 {@code updateSpeed=true}，读档不清）——覆盖任意方式放置
 * （玩家/机械手/蓝图炮/装置解体/指令）与区块加载；</li>
 * <li>方块状态变更（扳手转向、换壳等，{@code KineticBlock#updateIndirectNeighbourShapes}
 * 置 {@code updateSpeed}）、离合器/变速箱红石切换（{@code GearshiftBlock#tick}）；</li>
 * <li>发电机起转/变速（{@code GeneratingKineticBlockEntity#applyNewSpeed} 内
 * {@code attachKinetics}）。</li>
 * </ul>
 *
 * <p>HEAD 注入、只登记不查：同一时刻 Create 的传播尚未跑完，且在 BE tick 内拆方块会与
 * 引擎自身 tick 的 {@code setBlock(LIT)} 竞争；实际连通扫描在
 * {@link PortableEngineExclusivity} 的服务端 tick 末统一结算。</p>
 */
@Mixin(RotationPropagator.class)
public abstract class PortableEngineAttachMixin {

    @Inject(method = "handleAdded", at = @At("HEAD"), remap = false)
    private static void dimblend$onKineticAttached(Level world, BlockPos pos, KineticBlockEntity addedTE,
                                                   CallbackInfo ci) {
        PortableEngineExclusivity.onKineticAttached(world, pos, addedTE.getBlockState());
    }
}
