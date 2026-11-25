package dimblend.experience.mixin.compat.create;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainHud;
import dimblend.experience.compat.create.ItemDrainNetherEvaporation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 分液池护目镜 HUD：在 Create 自带的流体行（{@code containedFluidTooltip}）之后
 * 追加灌溉/蒸发/催熟状态行（口径见 {@link ItemDrainHud}；Jade 侧同文案）。
 *
 * <p>数据来源全为客户端可得：水量读客户端水箱 capability（与 Create 原行同路径，
 * null 面 = 内部水箱）；催熟剩余时间 = {@link ItemDrainHudSyncMixin} 同步的绝对
 * 击发时刻 − 客户端 gameTime（两端 gameTime 同步推进，显示平滑递减）；「蒸发中」
 * 由客户端自己按"有水 + 下界群系 + 开关 + 速率 &gt; 0"判（群系判定走 Sable 世界
 * 坐标投影，见 {@link ItemDrainNetherEvaporation#isNetherBiome(Level,
 * net.minecraft.core.BlockPos)}）。本方法仅护目镜渲染（客户端）调用，读的是
 * ConfigSync 副本，无服务端守卫问题。</p>
 *
 * <p>注入点：{@code addToGoggleTooltip(Ljava/util/List;Z)Z} 为目标类自身声明的
 * 接口实现，单一 RETURN，RETURN 注入安全。</p>
 */
@Mixin(ItemDrainBlockEntity.class)
public abstract class ItemDrainGoggleMixin {

    @Inject(method = "addToGoggleTooltip(Ljava/util/List;Z)Z", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$appendIrrigationHud(List<Component> tooltip, boolean isPlayerSneaking,
            CallbackInfoReturnable<Boolean> cir) {
        ItemDrainBlockEntity self = (ItemDrainBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, self.getBlockPos(), null);
        int waterMb = 0;
        if (handler != null && handler.getTanks() >= 1) {
            FluidStack stored = handler.getFluidInTank(0);
            // 只认水：分液池可能存其他流体（功能口径与 ItemDrainIrrigation 一致）
            if (stored.is(Fluids.WATER)) {
                waterMb = stored.getAmount();
            }
        }
        long nextBoostAt = ((ItemDrainHud.HasSync) this).dimblend$nextBoostAt();
        long boostTicksLeft = nextBoostAt >= 0 ? Math.max(0, nextBoostAt - level.getGameTime()) : -1;
        boolean evaporating = Config.isLoaded()
                && ItemDrainHud.shouldShowEvaporating(waterMb,
                        ItemDrainNetherEvaporation.isNetherBiome(level, self.getBlockPos()),
                        Config.ITEM_DRAIN_NETHER_EVAPORATION.get(),
                        Config.ITEM_DRAIN_NETHER_EVAPORATION_RATE_MB_PER_SECOND.get());
        int diameter = 2 * Config.ITEM_DRAIN_COVERAGE_RADIUS.get() + 1;
        List<Component> lines = ItemDrainHud.hudLines(
                new ItemDrainHud.Snapshot(waterMb, boostTicksLeft, evaporating, diameter));
        if (lines.isEmpty()) {
            return;
        }
        tooltip.addAll(lines);
        if (!cir.getReturnValue()) {
            cir.setReturnValue(true);
        }
    }
}
