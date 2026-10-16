package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;

/**
 * B12：空箱时在原始护目镜内容后追加两行灰字，文案来自双语 lang。
 * WrapMethod 每个目标恰好包装一次，覆盖普通/组合式/巨型的所有返回分支；
 * 保留上游 boolean 返回值，实际添加提示时返回 true。组合式非 controller
 * 委托 controller 的原生 tooltip 路径，其包装已追加提示，外层跳过避免重复。
 */
@Mixin({DieselEngineBlockEntity.class, ModularDieselEngineBlockEntity.class, HugeDieselEngineBlockEntity.class})
public abstract class CdgEngineGoggleFuelHintMixin {

    @WrapMethod(method = "addToGoggleTooltip(Ljava/util/List;Z)Z", require = 1, allow = 1)
    private boolean dimblend$appendNoFuelHint(List<Component> tooltip, boolean isPlayerSneaking,
            Operation<Boolean> original) {
        boolean shown = original.call(tooltip, isPlayerSneaking);
        IEngine engine = (IEngine) (Object) this;
        // 组合式非 controller 委托给 controller 实例渲染（其 RETURN 已追加），跳过防重复
        if (engine instanceof ModularDieselEngineBlockEntity modular && !modular.isController()) {
            return shown;
        }
        if (!engine.fs().isEmpty()) {
            return shown;
        }
        tooltip.add(Component.translatable("hud.dimblend_experience.diesel_engine_no_fuel")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("hud.dimblend_experience.diesel_engine_no_fuel_faster")
                .withStyle(ChatFormatting.GRAY));
        return true;
    }
}
