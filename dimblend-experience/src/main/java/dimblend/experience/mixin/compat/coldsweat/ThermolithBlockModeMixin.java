package dimblend.experience.mixin.compat.coldsweat;

import com.momosoftworks.coldsweat.common.block.ThermolithBlock;
import dimblend.experience.compat.coldsweat.ThermolithModeRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 {@code cold_sweat:thermolith} 的方块状态补一个 {@code dimblend_mode} 属性
 * （normal/ice/magma）。属性对象在 {@link ThermolithModeRules} 里，本 mixin 只做 add。
 *
 * <p>TAIL 注入而不是 {@code @Overwrite}：原版 {@code createBlockStateDefinition} 里那两条
 * （FACING、POWERED）保持 Cold Sweat 自己的写法，任何版本调整都无需同步本文件。</p>
 */
@Mixin(value = ThermolithBlock.class, remap = false)
public abstract class ThermolithBlockModeMixin {

    @Inject(method = "createBlockStateDefinition(Lnet/minecraft/world/level/block/state/StateDefinition$Builder;)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$addModeProperty(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(ThermolithModeRules.MODE);
    }
}
