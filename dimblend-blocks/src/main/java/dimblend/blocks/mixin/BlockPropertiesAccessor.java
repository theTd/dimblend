package dimblend.blocks.mixin;

import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * C0 硬度统一的豁免判据：读取 Properties 已设 destroyTime。
 *
 * <p>原版目标，无条件应用。包私有字段经 Shadow 机制读取（Mixin 对目标类
 * 成员可见性无要求）。方法名带 modid 前缀：与同环境其他 mod 的同类
 * accessor 隔离，避免同名方法重复注入同一目标类。</p>
 */
@Mixin(BlockBehaviour.Properties.class)
public interface BlockPropertiesAccessor {

    @Accessor("destroyTime")
    float dimblendblocks$getDestroyTime();
}
