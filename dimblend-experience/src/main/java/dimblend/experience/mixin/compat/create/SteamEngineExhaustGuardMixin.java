package dimblend.experience.mixin.compat.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;

import dimblend.experience.compat.create.SteamEngineExhaustAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 排气 32 秒里引擎右击全部拒绝（值框配置、任何物品交互）。
 *
 * <p>为什么不注入 {@code onWrenched}/{@code onSneakWrenched}：{@code javap -p} 核实
 * {@code SteamEngineBlock} <b>不声明</b>这两个方法——它们来自父类的 {@code IWrenchable} 实现，
 * Mixin 不能跨类改继承方法。扳手那条路改由 {@code SteamEngineExhaustGuard} 的
 * {@code PlayerInteractEvent.RightClickBlock}（按 {@code AllItems.WRENCH} 识别）统一挡掉，
 * 引擎和它的锅炉储罐格都覆盖；这里只补"其它右击"。</p>
 */
@Mixin(SteamEngineBlock.class)
public abstract class SteamEngineExhaustGuardMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$refuseUseWhileExhausting(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult,
            CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (SteamEngineExhaustAccess.isExhaustingAt(level, pos)) {
            cir.setReturnValue(ItemInteractionResult.FAIL);
        }
    }
}
