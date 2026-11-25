package dimblend.experience.mixin.compat.cdg;

import dimblend.experience.compat.cdg.CdgEngineDamage;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 放回"带损坏标记"的柴油机物品 → 落地的机器直接是损坏态（2026-11-25 防洗损坏口径）。
 *
 * <p>与 {@code CdgPistonDamageDropMixin}（挖掉时打标）成对：只挖不动、不换活塞，洗不掉损坏。
 * 判据全在 {@link CdgEngineDamage#hasDamagedTag(ItemStack)}（CUSTOM_DATA 里一个布尔），
 * 没有该组件的物品在 {@code place} 返回后走一次 {@code has()} 就结束，开销可忽略。</p>
 *
 * <p>只在服务端还原：客户端 BE 的附件是默认值，这里写不写都无所谓，统一留给服务端广播。</p>
 *
 * <p>{@code allow} 给到 16：{@code BlockItem#place} 的原版返回点有 6 条，但它是所有模组
 * 放方块的总入口，其它模组（Create / Sable / CDG 自己）常在这条链上加更早优先级的
 * {@code InteractionResult} 早退。{@code require = 1} 保证"至少有一条返回点被覆盖"，
 * {@code allow = 16} 只是给这些外来返回点留余量——写死 6 时，任何一个附属模组插进来
 * 都会变成启动期 {@code InjectionError}。</p>
 */
@Mixin(BlockItem.class)
public abstract class CdgPistonDamageRestoreMixin {

    @Inject(method = "place", at = @At("RETURN"), require = 1, allow = 16)
    private void dimblend$restorePistonDamage(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return;
        }
        if (context.getLevel().isClientSide()) {
            return;
        }
        if (!CdgEngineDamage.hasDamagedTag(context.getItemInHand())) {
            return;
        }
        CdgEngineDamage.restoreOnPlace(context.getLevel(), context.getClickedPos());
    }
}
