package dimblend.blocks.mixin.compat.create;

import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import dimblend.blocks.mixin.BlockPropertiesAccessor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * C0 Create 本体侧：伪装三件套（CopycatBlock / Panel / Step）硬度统一黑曜石。
 *
 * <p>{@link CopycatBlock} 是 WaterloggedCopycatBlock（Panel/Step）与全尺寸块的
 * 共同根，单点注入全覆盖。-1 豁免与 Copycats+ 侧
 * {@code CopycatsObsidianHardnessMixin} 同理；仅在 Create 在场时应用。</p>
 */
@Mixin(CopycatBlock.class)
public abstract class CreateCopycatObsidianHardnessMixin {

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
