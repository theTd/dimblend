package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import dimblend.experience.compat.simulated.PortableEngineExclusivity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F2 便携引擎起转钩子：{@code GeneratingKineticBlockEntity.applyNewSpeed} 是
 * Create 发电机启停的收敛点（6.0.10-281 字节码核实：{@code updateGeneratedRotation}
 * 在 {@code speed != generated} 时调用 {@code applyNewSpeed(oldSpeed, newSpeed)}；
 * 旧速 0、新速非零即本机开始对外输出转速）。
 *
 * <p>HEAD 注入、只登记不查网：此时 {@code setSpeed/setNetwork/attachKinetics}
 * 都还没跑（同方法起转分支顺序），同步枚举必为空——实际检查由
 * {@link PortableEngineExclusivity#onStartedGenerating} 登记、
 * tick 末结算（合网已完成）。内部再按方块注册表名收敛到 16 色引擎，
 * 非引擎发电机直接返回。目标为 Create 公共类（编译依赖已有），
 * simulated 缺席时方块名对不上、自然放行，故 mixin 常驻、plugin 按 create 在场性过滤。</p>
 */
@Mixin(GeneratingKineticBlockEntity.class)
public abstract class PortableEngineStartMixin {

    @Inject(method = "applyNewSpeed(FF)V", at = @At("HEAD"), remap = false)
    private void dimblend$checkEngineExclusivity(float oldSpeed, float newSpeed, CallbackInfo ci) {
        if (oldSpeed != 0.0F || newSpeed == 0.0F) {
            return;
        }
        GeneratingKineticBlockEntity self = (GeneratingKineticBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        BlockPos pos = self.getBlockPos();
        PortableEngineExclusivity.onStartedGenerating(level, pos);
    }
}
