package dimblend.blocks.mixin;

import javax.annotation.Nullable;

import dimblend.blocks.fragile.WaterBucketLavaQuench;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 水桶浇岩浆的兜底层：{@code BucketItem#emptyContents} 入口先问
 * {@link WaterBucketLavaQuench#tryInterceptEmptyContents}，目标格是岩浆就当场固化并返回 true，
 * 原版那条「setBlock(水)」路径整段不执行。
 *
 * <p>挂在 NeoForge 的 5 参重载（多一个 {@code ItemStack container}）上：4 参的
 * {@code @Deprecated} 重载只是转调它，所以两条入口一并覆盖；原版内部对相邻格的那次递归
 * 也走 5 参，故「点干燥面、目标格在岩浆里」同样被接住。</p>
 *
 * <p>目标类是原版 {@code BucketItem}，无外部依赖，mixin 插件无条件应用。</p>
 */
@Mixin(BucketItem.class)
public abstract class BucketItemLavaQuenchMixin {

    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;"
                    + "Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void dimblendblocks$quenchLavaBeforeEmptying(@Nullable Player player, Level level, BlockPos pos,
            @Nullable BlockHitResult hitResult, @Nullable ItemStack container,
            CallbackInfoReturnable<Boolean> cir) {
        if (WaterBucketLavaQuench.tryInterceptEmptyContents((BucketItem) (Object) this, player, level, pos)) {
            cir.setReturnValue(true);
        }
    }
}
