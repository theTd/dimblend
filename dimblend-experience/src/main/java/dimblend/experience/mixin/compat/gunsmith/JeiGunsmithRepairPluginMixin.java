package dimblend.experience.mixin.compat.gunsmith;

import java.util.List;

import dimblend.experience.compat.gunsmith.GunsmithJeiRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 改写 gundb 的 JEI 铁砧修复配方：删除全部配件修复配方，将枪械右侧材料替换为对应枪型满修数量
 * （1/2/1/3/4/3/1）的 {@code create:precision_mechanism}。
 *
 * <p>目标 {@code getGunRecipes} 的参数类型均属 JEI（不在编译 classpath 上），
 * 采用零目标参数的 {@code @Inject(RETURN)} 避免编译期类型引用。</p>
 */
@Pseudo
@Mixin(targets = "mod.cdv.gdb.jei.JeiRepairPlugin", remap = false)
public abstract class JeiGunsmithRepairPluginMixin {

    @Inject(
            method = "getGunRecipes(Lmezz/jei/api/recipe/vanilla/IVanillaRecipeFactory;Lmezz/jei/api/runtime/IIngredientManager;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            allow = 1)
    private void dimblend$rewriteRepairRecipes(CallbackInfoReturnable<List<?>> cir) {
        Object filtered = GunsmithJeiRecipes.filter(cir.getReturnValue());
        if (filtered instanceof List<?> list) {
            cir.setReturnValue(list);
        }
    }
}
