package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.LinkWithBulbBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 取消显示连接器运行期与 Sable 结构迁移期的距离裁切。
 *
 * <p>Sable 的 {@code DisplayLinkBlockEntityMixin} 在 {@code getTargetPosition} 的 {@code TAIL}
 * 读取 {@code displayLinkRange} 并在超距时把返回值改成 {@link BlockPos#ZERO}；此处在 {@code HEAD}
 * 直接返回 {@code worldPosition.offset(targetOffset)}，无需改动 Create 配置（亦避免配置值平方的整型溢出）。</p>
 */
@Mixin(DisplayLinkBlockEntity.class)
public abstract class DisplayLinkUnlimitedTargetMixin extends LinkWithBulbBlockEntity {

    @Shadow(remap = false)
    private BlockPos targetOffset;

    private DisplayLinkUnlimitedTargetMixin(
            BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Inject(
            method = "getTargetPosition()Lnet/minecraft/core/BlockPos;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            allow = 1)
    private void dimblend$returnUnclampedTargetPosition(CallbackInfoReturnable<BlockPos> cir) {
        cir.setReturnValue(this.worldPosition.offset(this.targetOffset));
    }
}
