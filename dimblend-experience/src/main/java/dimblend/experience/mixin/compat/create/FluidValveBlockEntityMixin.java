package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlock;
import com.simibubi.create.content.fluids.pipes.valve.FluidValveBlockEntity;

import dimblend.experience.compat.create.FluidValveSnapRules;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 流体阀门状态同步 + 未到位指针回拉（LOCKED DECISION #12 {@code valve_snap_revert}）。
 *
 * <p>三个口子（全部不碰私有字段以外的内部状态，口径见 {@link FluidValveSnapRules}）：</p>
 *
 * <ol>
 *   <li><b>同步</b>：{@code LerpedFloat.readNBT(tag, client=true)} 只在 tag 带 {@code Force}
 *       时覆盖客户端数值（否则只读 chaser）。原版 {@code write} 从不置 forcedSync，于是
 *       摇杆转停后的指针值根本同步不到客户端。这里在 {@code write} 序列化 {@code pointer}
 *       之前调一次 {@code forceNextSync()}。</li>
 *   <li><b>翻开关要 sendData</b>：{@code tick()} 用 {@code switchToBlockState} 翻
 *       {@code ENABLED}，但不发数据包，客户端方块态与指针漂移。外层 {@code @WrapMethod}
 *       比对调用前后的 {@code ENABLED}，一变就补 {@code sendData()}（不用逐个 RETURN
 *       注入：{@code tick()} 有 4 个 return）。</li>
 *   <li><b>未到位回拉</b>：转速为 0 且 {@code 0 < pointer < 1} 时<b>不翻 ENABLED</b>，
 *       把指针按 {@code enabled ? 1 : 0} 每 tick 走 {@code 0.25} 回拉，到位即
 *       {@code updateChaseTarget} + {@code sendData()}，两端严格一致（判定见
 *       {@link FluidValveSnapRules}）。</li>
 * </ol>
 *
 * <p>为什么不用 {@code @Inject(at = @At("RETURN"))}：{@code tick()} 有 4 个 return
 * （客户端早退 + 两种翻法各一 + 结尾），{@code @WrapMethod} 一次包住整段更稳。</p>
 */
@Mixin(FluidValveBlockEntity.class)
public abstract class FluidValveBlockEntityMixin {

    @Shadow(remap = false)
    private LerpedFloat pointer;

    @WrapOperation(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/createmod/catnip/animation/LerpedFloat;writeNBT()Lnet/minecraft/nbt/CompoundTag;"),
            remap = false, require = 1, allow = 1)
    private net.minecraft.nbt.CompoundTag dimblend$forcePointerSync(LerpedFloat pointer,
            Operation<net.minecraft.nbt.CompoundTag> original) {
        // 客户端 readNBT 只认带 Force 的 tag；不置位的话指针值永远同步不过去
        pointer.forceNextSync();
        return original.call(pointer);
    }

    @WrapMethod(method = "tick()V", remap = false, require = 1, allow = 1)
    private void dimblend$tickWithPointerSnap(Operation<Void> original) {
        FluidValveBlockEntity self = (FluidValveBlockEntity) (Object) this;
        Level level = self.getLevel();
        boolean enabledBefore = this.dimblend$isEnabled(self.getBlockState());
        original.call();
        boolean enabledAfter = this.dimblend$isEnabled(self.getBlockState());
        if (level == null || level.isClientSide) {
            return;
        }
        if (enabledBefore != enabledAfter) {
            self.sendData();
        }
        if (!FluidValveSnapRules.isStationary(self.getSpeed())) {
            return;
        }
        float value = this.pointer.getValue();
        if (!FluidValveSnapRules.isUnfinished(value)) {
            return;
        }
        float target = FluidValveSnapRules.snapTarget(enabledAfter);
        float next = FluidValveSnapRules.stepToward(value, target, FluidValveSnapRules.SNAP_STEP_PER_TICK);
        this.pointer.setValue(next);
        this.pointer.updateChaseTarget(target);
        if (FluidValveSnapRules.reached(next, target)) {
            this.pointer.setValue(target);
            self.sendData();
        }
    }

    private boolean dimblend$isEnabled(BlockState state) {
        return state.getBlock() instanceof FluidValveBlock && state.getValue(FluidValveBlock.ENABLED);
    }
}
