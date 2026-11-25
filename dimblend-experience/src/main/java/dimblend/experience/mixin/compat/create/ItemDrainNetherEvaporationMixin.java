package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainNetherEvaporation;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * N6 分液池下界蒸发：{@code ItemDrainBlockEntity} 位于下界群系时，服务端每 10 tick
 * 把池内存水按配置速率蒸干（见 {@link ItemDrainNetherEvaporation}）；非下界群系/非水/
 * 关开关时不扣水。客户端同口径冒出稀疏 {@code CLOUD} 粒子（"水在下界被烤干"的观感）。
 *
 * <p>注入点与守卫口径同 {@code ItemDrainIrrigationMixin} / {@code ItemDrainGrowthBoostMixin}：
 * 空载分液池 {@code tick()} 开头提前 return，TAIL 不可达，只能用
 * {@code tick()V} HEAD；服务端判定必须先于 {@code Config} 读取（客户端不同步 SERVER
 * 配置，未加载时 {@code Value#get()} 抛 ISE），再用 {@code gameTime % 10} 降拍。
 * {@code invalidate()} 只有单一 RETURN，TAIL 清余数安全。</p>
 */
@Mixin(ItemDrainBlockEntity.class)
public abstract class ItemDrainNetherEvaporationMixin implements ItemDrainNetherEvaporation.HasState {

    @Unique
    private final ItemDrainNetherEvaporation.State dimblend$netherEvaporation = new ItemDrainNetherEvaporation.State();

    @Override
    public ItemDrainNetherEvaporation.State dimblend$netherEvaporationState() {
        return this.dimblend$netherEvaporation;
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$evaporateNetherWater(CallbackInfo ci) {
        ItemDrainBlockEntity self = (ItemDrainBlockEntity) (Object) this;
        // HEAD 双端都会进：SERVER 配置只在服务端可读，客户端守卫必须在 Config 读取之前
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!Config.ITEM_DRAIN_NETHER_EVAPORATION.get()) {
            ItemDrainNetherEvaporation.reset(this.dimblend$netherEvaporation);
            return;
        }
        if (serverLevel.getGameTime() % 10 != 0) {
            return;
        }
        ItemDrainNetherEvaporation.tick(serverLevel, self, this.dimblend$netherEvaporation);
    }

    /** 客户端：每 tick 一次稀疏云粒子（水量读客户端已同步的水箱，口径同护目镜）。 */
    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$spawnEvaporationParticles(CallbackInfo ci) {
        ItemDrainBlockEntity self = (ItemDrainBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || !level.isClientSide) {
            return;
        }
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK,
                self.getBlockPos(), Direction.DOWN);
        int waterMb = 0;
        if (handler != null && handler.getTanks() >= 1) {
            FluidStack stored = handler.getFluidInTank(0);
            if (stored.is(Fluids.WATER)) {
                waterMb = stored.getAmount();
            }
        }
        ItemDrainNetherEvaporation.clientParticleTick(level, self.getBlockPos(), waterMb);
    }

    @Inject(method = "invalidate()V", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$resetEvaporationOnInvalidate(CallbackInfo ci) {
        // 双端可跑：只碰自身字段，不读 SERVER 配置
        ItemDrainNetherEvaporation.reset(this.dimblend$netherEvaporation);
    }
}
