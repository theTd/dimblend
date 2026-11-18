package dimblend.experience.mixin.compat.toolbelt;

import java.util.Optional;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.toolbelt.ToolBeltBackpackGuard;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 客户端侧的第二道门：手持背包（主手或副手）时，ToolBelt 的快捷切换键不打开径向菜单。
 *
 * <p>目标是 {@code ClientEvents#handleKeys} 中唯一一次
 * {@code BeltFinder.findBelt(LivingEntity) -> Optional<BeltGetter>} 调用；返回
 * {@link Optional#empty()} 等价于“没有腰带”，既不会构造也无法打开 {@code RadialMenuScreen}，
 * 同时不影响同方法里腰带槽物品栏的 {@code OPEN_BELT_SLOT_KEYBIND} 分支。</p>
 *
 * <p>背包判据见 {@link ToolBeltBackpackGuard}；这里不引用任何 ToolBelt 内部类型，
 * 描述符已用 {@code javap} 从运行 jar 实读。</p>
 */
@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.client.ClientEvents", remap = false)
public abstract class ToolBeltRadialMenuBackpackGuardMixin {

    @WrapOperation(
            method = "handleKeys(Lnet/neoforged/neoforge/client/event/ClientTickEvent$Pre;)V",
            at = @At(value = "INVOKE",
                    target = "Ldev/gigaherz/toolbelt/BeltFinder;findBelt(Lnet/minecraft/world/entity/LivingEntity;)Ljava/util/Optional;"),
            require = 1,
            allow = 1)
    private static Optional<?> dimblend$blockRadialMenuWhileHoldingBackpack(
            LivingEntity entity, Operation<Optional<?>> original) {
        if (ToolBeltBackpackGuard.isHoldingBackpack(entity)) {
            return Optional.empty();
        }
        return original.call(entity);
    }
}
