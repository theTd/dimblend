package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.compat.cca.SableWireBreakRules;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 断钩即清理跨载具电线（2026-11-25 用户口径）。
 *
 * <p>在 {@code removePartner} 的 <b>HEAD</b> 取快照：此刻车钩的
 * {@code sable$getConnectionDependencies()} 仍指向被耦合的那台载具，
 * 正好给出要清理的两端。先断开跨域电线，再交给上游完成解锁——反过来的话
 * 依赖已经消失，反扫就找不到对端了。</p>
 */
@Mixin(value = AutomaticCouplerBlockEntity.class, remap = false)
public abstract class AutomaticCouplerWireBreakMixin {

    @Inject(method = "removePartner", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$breakCrossSubLevelWires(CallbackInfo ci) {
        AutomaticCouplerBlockEntity self = (AutomaticCouplerBlockEntity) (Object) this;
        if (self.getLevel() == null || !SableWireBreakRules.available()) {
            return;
        }
        SubLevel own = dimblend.experience.compat.cca.SableCoupledWireRules.subLevelOf(self);
        if (own == null) {
            return;
        }
        List<SubLevel> partners = new ArrayList<>();
        for (SubLevel dependency : self.sable$getConnectionDependencies()) {
            if (dependency != null && dependency != own) {
                partners.add(dependency);
            }
        }
        for (SubLevel partner : partners) {
            SableWireBreakRules.breakCrossWires(self.getLevel(), own, partner);
        }
    }
}
