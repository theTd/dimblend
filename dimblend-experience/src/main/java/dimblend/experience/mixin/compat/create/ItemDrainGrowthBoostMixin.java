package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainGrowthBoost;
import net.minecraft.server.level.ServerLevel;

/**
 * 分液池催熟：{@code ItemDrainBlockEntity} 的存水不低于阈值时，按周期随机催熟
 * 范围内一株植物（见 {@link ItemDrainGrowthBoost}），低于阈值/关开关时停催。
 *
 * <p>注入点（{@code tick()V} HEAD + {@code invalidate()V} TAIL）的选择理由与
 * 守卫口径同 {@code ItemDrainIrrigationMixin}：空载分液池 {@code tick()} 开头提前
 * return，TAIL 不可达，故用 HEAD + 内部降拍守卫；父类方法不碰。</p>
 */
@Mixin(ItemDrainBlockEntity.class)
public abstract class ItemDrainGrowthBoostMixin implements ItemDrainGrowthBoost.HasState {

    @Unique
    private final ItemDrainGrowthBoost.State dimblend$growth = new ItemDrainGrowthBoost.State();

    @Override
    public ItemDrainGrowthBoost.State dimblend$growthState() {
        return this.dimblend$growth;
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void dimblend$growPlants(CallbackInfo ci) {
        ItemDrainBlockEntity self = (ItemDrainBlockEntity) (Object) this;
        // HEAD 双端都会进：SERVER 配置只在服务端可读，客户端守卫必须在 Config 读取之前
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!Config.ITEM_DRAIN_GROWTH.get()) {
            ItemDrainGrowthBoost.reset(this.dimblend$growth);
            return;
        }
        if (serverLevel.getGameTime() % 10 != 0) {
            return;
        }
        ItemDrainGrowthBoost.tick(serverLevel, self, this.dimblend$growth);
    }

    @Inject(method = "invalidate()V", at = @At("TAIL"))
    private void dimblend$resetGrowthOnInvalidate(CallbackInfo ci) {
        // 双端可跑：只碰自身字段，不读 SERVER 配置
        ItemDrainGrowthBoost.reset(this.dimblend$growth);
    }
}
