package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainIrrigation;
import net.minecraft.server.level.ServerLevel;

/**
 * 分液池保湿（免费保湿、无催熟，催熟另见 {@code ItemDrainGrowthBoostMixin}）：
 * {@code ItemDrainBlockEntity} 的水箱只要有水，就给周围同层土壤维持
 * {@code FarmlandWaterManager} 保湿票据（见 {@link ItemDrainIrrigation}），
 * 空水箱/非水/关开关时摘票。
 *
 * <p>注入点说明：只碰目标类自身声明的方法——{@code tick()V} HEAD 与
 * {@code invalidate()V} TAIL（拆除/卸载摘票）。tick 不能用 TAIL：Mixin 的
 * TAIL（BeforeFinalReturn）只落在方法<b>最后一个</b> RETURN 上，而
 * {@code ItemDrainBlockEntity.tick()} 在 {@code heldItem == null}（分液池空载、
 * 纯当水箱用——灌溉场景常态）时于方法开头提前 return，永远走不到末尾，
 * TAIL 注入的灌溉逻辑实际不可达；HEAD 每 tick 进一次，内部守卫
 * （服务端 / 总开关 / {@code gameTime % 10} 降拍）保证成本不变。
 * {@code invalidate()} 只有单一 RETURN，TAIL 安全。父类
 * {@code SmartBlockEntity} 声明的 {@code lazyTick/onChunkUnloaded} 不碰
 * （Mixin 0.8.5 只遍历目标类自身 methods，父类方法注入无法 apply）。
 * 区块卸载的票据由 {@code FarmlandWaterManager} 按 master chunk 自动清理，
 * 重载后下一拍按 {@code isValid} 重建。</p>
 */
@Mixin(ItemDrainBlockEntity.class)
public abstract class ItemDrainIrrigationMixin implements ItemDrainIrrigation.HasState {

    @Unique
    private final ItemDrainIrrigation.State dimblend$irrigation = new ItemDrainIrrigation.State();

    @Override
    public ItemDrainIrrigation.State dimblend$irrigationState() {
        return this.dimblend$irrigation;
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void dimblend$irrigate(CallbackInfo ci) {
        ItemDrainBlockEntity self = (ItemDrainBlockEntity) (Object) this;
        // HEAD 双端都会进：SERVER 配置只在服务端可读，客户端守卫必须在 Config 读取之前
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!Config.ITEM_DRAIN_IRRIGATION.get()) {
            ItemDrainIrrigation.drop(this.dimblend$irrigation);
            return;
        }
        if (serverLevel.getGameTime() % 10 != 0) {
            return;
        }
        ItemDrainIrrigation.tick(serverLevel, self, this.dimblend$irrigation);
    }

    @Inject(method = "invalidate()V", at = @At("TAIL"))
    private void dimblend$dropTicketOnInvalidate(CallbackInfo ci) {
        // 双端可跑：只碰自身字段，不读 SERVER 配置
        ItemDrainIrrigation.drop(this.dimblend$irrigation);
    }
}
