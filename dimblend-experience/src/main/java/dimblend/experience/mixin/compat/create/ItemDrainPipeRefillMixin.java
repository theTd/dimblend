package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainNetherHeat;
import dimblend.experience.compat.create.ItemDrainPipeRefill;
import net.minecraft.server.level.ServerLevel;

/**
 * 分液池管道补水：每 tick 在 {@code ItemDrainBlockEntity.tick()} 开头按开关双向置位
 * 水箱注入口——开则置回允许、关则置回禁止（设计说明见 {@link ItemDrainPipeRefill}）。
 *
 * <p>注入点选择理由同 {@code ItemDrainIrrigationMixin}：空载分液池 {@code tick()}
 * 开头提前 return，TAIL 不可达，只能用 HEAD。守卫口径：服务端判定必须先于
 * {@code Config} 读取（配置未加载/未同步时 {@code Value#get()} 抛 ISE，且本路径
 * 只对服务端有意义）。开关关闭时逐拍复位为禁止 = 原版常态（热关闭下一拍生效）。</p>
 *
 * <p><b>「下界太热」④ 已取消（2026-11-25）</b>：下界群系不再禁管道注水，闸门只看开关，
 * 与主世界同一分支；桶右键倒水路径不受影响（走 {@code ItemDrainBlock#useItemOn} 自己
 * 开的注水窗口）。</p>
 */
@Mixin(ItemDrainBlockEntity.class)
public abstract class ItemDrainPipeRefillMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$applyPipeRefill(CallbackInfo ci) {
        ItemDrainBlockEntity self = (ItemDrainBlockEntity) (Object) this;
        // HEAD 双端都会进：Config 读取必须在服务端判定之后（未加载时 Value#get 抛 ISE）
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // ④：下界不再禁管道注水（2026-11-25 裁定取消），闸门只看开关；
        //    第二参数保留旧调用口径，不参与判定（也省掉每拍一次群系查询）
        if (ItemDrainNetherHeat.pipeIntakeAllowed(Config.ITEM_DRAIN_PIPE_REFILL.get(), false)) {
            ItemDrainPipeRefill.openInsertion(self);
        } else {
            ItemDrainPipeRefill.closeInsertion(self);
        }
    }
}
