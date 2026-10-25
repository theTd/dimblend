package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainNetherEvaporation;
import net.minecraft.server.level.ServerLevel;

/**
 * N6 分液池下界蒸发：{@code ItemDrainBlockEntity} 位于下界群系时，服务端每 10 tick
 * 把池内存水按配置速率蒸干（见 {@link ItemDrainNetherEvaporation}）；非下界群系/非水/
 * 关开关时不扣水。
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

    @Inject(method = "tick()V", at = @At("HEAD"))
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

    @Inject(method = "invalidate()V", at = @At("TAIL"))
    private void dimblend$resetEvaporationOnInvalidate(CallbackInfo ci) {
        // 双端可跑：只碰自身字段，不读 SERVER 配置
        ItemDrainNetherEvaporation.reset(this.dimblend$netherEvaporation);
    }
}
