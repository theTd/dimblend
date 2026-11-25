package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock;

import dimblend.experience.compat.tavern.TapAttachmentRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拆酒桶时连带拆掉挂在出酒口上的龙头（龙头不能凭空留在原地）。
 *
 * <p>上游 {@code handleRemove} 只遍历 3×3×3 桶体，龙头在桶外一格，不在遍历范围内；
 * 而桶体被 destroy 后的形状更新不保证覆盖到龙头（龙头没有 {@code canSurvive}，
 * 见 {@code TapBlockMixin}）。所以这里在 {@code handleRemove} 的每个 return（
 * 客户端早退 + 正常结束，共 2 处）之后补一次：按桶原点算出龙头位置，朝向匹配就
 * {@code destroyBlock(tapPos, drop)}，{@code drop} 与上游同口径（创造模式不掉落）。</p>
 *
 * <p>参数签名以 javap 为准：{@code handleRemove(Level, BlockPos, BlockState, Player)}，
 * 第四个参数是<b>玩家</b>而不是 {@code boolean drop}。</p>
 */
@Mixin(value = BarrelBlock.class, remap = false)
public abstract class BarrelBlockMixin {

    @Inject(method = "handleRemove", at = @At("RETURN"), remap = false, require = 1, allow = 2)
    private static void dimblend$destroyTapWithBarrel(Level level, BlockPos pos, BlockState state, Player player,
            CallbackInfo ci) {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean drop = player == null || !player.isCreative();
        BlockPos origin = BarrelBlock.getOriginPos(pos, state);
        Direction facing = state.getValue(BarrelBlock.FACING);
        BlockPos tapPos = TapAttachmentRules.tapPos(origin, facing);
        BlockState tapState = level.getBlockState(tapPos);
        if (tapState.getBlock() instanceof TapBlock && tapState.getValue(TapBlock.FACING) == facing) {
            level.destroyBlock(tapPos, drop);
        }
    }
}
