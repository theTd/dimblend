package dimblend.experience.mixin;

import dimblend.experience.Config;
import dimblend.experience.compat.copycat.CopycatObsidianHardness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.extensions.IBlockStateExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 伪装方块爆抗与末影龙/凋灵破坏免疫统一黑曜石（NeoForge {@code IBlockStateExtension}
 * 目标，无条件应用）。
 *
 * <p>伪装方块重写 NeoForge 四参 {@code Block#getExplosionResistance} 与
 * {@code canEntityDestroy} 转发贴图材质且跳过 {@code super}，{@code Block} 层
 * mixin 会漏；{@link IBlockStateExtension} 默认方法是调用方，HEAD 取消在此分发前
 * 代入黑曜石。
 *
 * <p>开关门控同 {@link CopycatBlockStateBaseMixin}（{@code copycatObsidianHardness}，
 * 关闭即透传原版）。
 */
@Mixin(IBlockStateExtension.class)
public interface CopycatBlockStateExtensionMixin {

    @Inject(method = "getExplosionResistance", at = @At("HEAD"), cancellable = true, remap = false)
    private void dimblend$copycatObsidianExplosionResistance(
            BlockGetter level, BlockPos pos, Explosion explosion, CallbackInfoReturnable<Float> cir) {
        if (!Config.COPYCAT_OBSIDIAN_HARDNESS.get()) {
            return;
        }
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(CopycatObsidianHardness.obsidianExplosionResistance());
        }
    }

    @Inject(method = "canEntityDestroy", at = @At("HEAD"), cancellable = true, remap = false)
    private void dimblend$copycatObsidianCanEntityDestroy(
            BlockGetter level, BlockPos pos, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.COPYCAT_OBSIDIAN_HARDNESS.get()) {
            return;
        }
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(Blocks.OBSIDIAN.defaultBlockState().canEntityDestroy(level, pos, entity));
        }
    }
}
