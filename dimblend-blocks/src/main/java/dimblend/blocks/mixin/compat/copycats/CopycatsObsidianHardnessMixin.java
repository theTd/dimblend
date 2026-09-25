package dimblend.blocks.mixin.compat.copycats;

import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlock;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlock;
import dimblend.blocks.mixin.BlockPropertiesAccessor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * C0 Copycats+ 侧：全体伪装方块硬度统一黑曜石（destroyTime 50 / resistance 1200）。
 *
 * <p>两根基类（{@link CCCopycatBlock} 系 + {@link MultiStateCopycatBlock} 系，
 * 覆盖半砖/粱/板等全部变体）构造器入口改写传入的 Properties——注入点在 super
 * 调用之前，改的是 BlockBehaviour 消费前的值，故对成品方块有效。
 * 已是 -1（本模组创造变体的不可破坏）直接豁免；仅在 Copycats+ 在场时应用
 *（见 DimBlendBlocksMixinPlugin）。</p>
 */
@Mixin({CCCopycatBlock.class, MultiStateCopycatBlock.class})
public abstract class CopycatsObsidianHardnessMixin {

    private static final float OBSIDIAN_DESTROY_TIME = 50.0F;
    private static final float OBSIDIAN_RESISTANCE = 1200.0F;

    @ModifyVariable(method = "<init>", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static BlockBehaviour.Properties dimblendblocks$obsidian(BlockBehaviour.Properties props) {
        if (((BlockPropertiesAccessor) props).dimblendblocks$getDestroyTime() == -1.0F) {
            return props;
        }
        return props.strength(OBSIDIAN_DESTROY_TIME, OBSIDIAN_RESISTANCE);
    }
}
