package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.api.util.ChunkAvailability;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dimblend.experience.Config;
import dimblend.experience.compat.simurail.ChaseBrakeBogeyProbe;
import dimblend.experience.compat.simurail.ChaseBrakeSignalRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * E9 转向架红石信号强度上限（全维度）：转向架收到的红石信号强度不高于上限
 * （{@code trainBrakeSignalCap}，1-15 级，默认 1）的原样生效，超过上限的一律压到上限。
 *
 * <p>v1.14~v1.16 为布尔 {@code trainFixedBrakeSignal}（任意正强度一律压到 1 级），
 * v1.17 改为上限可调——旧口径即上限 1 的特例，上限 15 时 {@code min} 恒取原值
 * （等效关闭，恢复原版随强度线性）。</p>
 *
 * <p>字节码事实（分支构建 fix/sync-chunk-load-ecd2dd3（基线 ecd2dd3）反汇编）：
 * {@code getControlStrength()D} = clamp({@code getSignalSafe(above 或 below, UP/DOWN)} / 15D, 0, 1)，
 * 是转向架红石信号的唯一归一化入口——同步读块修复后信号经 {@code getSignalSafe}
 * （ChunkAvailability 守卫，未加载区块读作 0，不触发同步加载），不影响本 RETURN 处压顶。
 * {@code getBrakeStrength()D} 按 {@code options.controlMode} 取其原值
 * （正控）或 {@code 1 -} 其（反控），{@code calculateStressApplied} 同按 controlMode 三分支
 * 取其 × {@code options.stress} 计动力网络应力（正控 control、反控 1−control、默认恒 1.0
 * 与信号无关——默认模式下本 mixin 零效果）。本 mixin 在 {@code getControlStrength} RETURN
 * 把超过 上限/15 的强度压到 上限/15、0 保持 0：刹车力度与应力同按修正后强度生效，施力方
 * （{@code PhysicsBogeyAxle}）与 E3/E4 沿检测
 * （{@link PhysicsBogeyBrakeSoundMixin} 读 {@code getBrakeStrength}）自动一致。</p>
 *
 * <p><b>I20 刹车车厢例外</b>（{@link ChaseBrakeSignalRules}）：追逐的刹车车厢靠红石块
 * 提供 15 级信号，若也按上限 1 压到 1/15，整节车只剩 6.7% 制动力，且 1..15 级完全不可调。
 * 识别先读 {@link ChaseBrakeBogeyMarker}（建车时置位、NBT 持久），未标记时回落到
 * 「正上方红石块 + 再上方红石灯」的建车电路特征（旧存档里已生成的刹车车厢）。例外分支对任意
 * 正信号返回 1.0（满刹），0 信号仍返回 0；方块读取与 {@code getSignalSafe} 同用
 * {@code ChunkAvailability} 守卫，物理线程上不会同步加载区块。</p>
 *
 * <p>注意反控模式二阶后果（接受项）：刹车恒 ≥(15−上限)/15 不归零，
 * E3/E4 施闸/松闸沿不再触发。</p>
 *
 * <p>{@code getSteerValue} 直接读各面 {@code getSignal} 差值做转向，不经
 * {@code getControlStrength}，不在本条目范围。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyBrakeSignalCapMixin {

    @ModifyReturnValue(method = "getControlStrength()D", at = @At("RETURN"), remap = false)
    private double dimblend$clampSignalStrength(double original) {
        if (original <= 0.0) {
            return original;
        }
        boolean chaseBrakeBogey = ModList.get().isLoaded("dimblend")
                && ChaseBrakeBogeyProbe.isMarked(this);
        if (!chaseBrakeBogey) {
            chaseBrakeBogey = this.dimblend$isBrakeCarCircuit();
        }
        return ChaseBrakeSignalRules.applyCap(original, Config.TRAIN_BRAKE_SIGNAL_CAP.get(), chaseBrakeBogey);
    }

    /**
     * 旧存档兜底：建车流程（{@code CarPlans}）在转向架正上方放红石块、再上方放红石灯。
     * 两格都读到才算——单红石块也是普通转向架的正常手动通电方式。
     */
    @Unique
    private boolean dimblend$isBrakeCarCircuit() {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        BlockPos above = self.getBlockPos().above();
        BlockPos aboveAbove = above.above();
        if (!ChunkAvailability.areChunksLoaded(level, above, aboveAbove)) {
            return false;
        }
        return ChaseBrakeSignalRules.isChaseBrakeCircuit(
                level.getBlockState(above).is(Blocks.REDSTONE_BLOCK),
                level.getBlockState(aboveAbove).is(Blocks.REDSTONE_LAMP));
    }
}
