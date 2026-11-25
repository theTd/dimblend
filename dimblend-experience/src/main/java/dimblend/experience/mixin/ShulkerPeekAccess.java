package dimblend.experience.mixin;

import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 潜影贝闭合态的只读访问器（{@link ShulkerClosedImmunityMixin} 用）。
 *
 * <p>上游 {@code getRawPeekAmount()} 与 {@code currentPeekAmount} 均为 private，
 * @Invoker / @Accessor 是官方惯例，不受"mixin 成员一律 private"约束。</p>
 */
@Mixin(Shulker.class)
public interface ShulkerPeekAccess {

    @Invoker("getRawPeekAmount")
    int dimblend$rawPeekAmount();

    @Accessor("currentPeekAmount")
    float dimblend$currentPeekAmount();
}
