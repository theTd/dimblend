package dimblend.experience.mixin.compat.simurail;

import java.util.List;

import com.crystaelix.simurail.api.bogey.menu.BogeyCategory;
import com.crystaelix.simurail.api.bogey.menu.BogeyMenuSelection;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyMenuScreen;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dimblend.experience.compat.simurail.SimurailSurvivalBogeyGlue;
import dimblend.experience.compat.simurail.SimurailSurvivalBogeyRules;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 生存模式的转向架菜单只列四种样式（2026-11-25 LOCKED #3 {@code bogey_survival_menu}）。
 *
 * <p>菜单构造时把主分类压成一个"生存可用样式"分类（隐形 / 宽轨标准 / 宽轨
 * scotch yoke 大 / 特大）；当前已选样式不在白名单时改用一个<b>不预选任何样式</b>
 * 的选择状态，保证打开界面不会自动把车改成白名单样式。创建模式不受影响。</p>
 *
 * <p>顺带修掉 {@code changeMainCategory} 里 {@code BogeyMenuManager.getCategories()}
 * 无参重载（返回的是非 inverted 列表，翻轨载具的主分类会错位）：直接复用已过滤的
 * {@code mainCategories}。</p>
 *
 * <p>拦截点必须是<b>非静态</b>的 {@code List#get(int)}（运行 jar
 * {@code simurail-1.21.1-0.0.0-a+ecd2dd3.jar} 字节码：{@code changeMainCategory} 在偏移 13
 * {@code invokestatic BogeyMenuManager.getCategories()} 之后偏移 17
 * {@code invokeinterface List.get(I)}，全方法唯一一处）。拿 {@code getCategories()} 当
 * {@code @Redirect} 目标是错的：只有 {@code List.get} 这一处能把"取哪一条"换成
 * {@code mainCategories}，且 handler 必须带 {@code List} 接收者 + {@code int} 下标两个形参。</p>
 */
@Mixin(value = PhysicsBogeyMenuScreen.class, remap = false)
public abstract class BogeyMenuScreenSurvivalMixin {

    @Shadow
    @Final
    boolean inverted;

    @Shadow
    @Final
    List<BogeyCategory<?>> mainCategories;

    @ModifyExpressionValue(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/api/bogey/menu/BogeyMenuManager;getCategories(Z)Ljava/util/List;"),
            require = 1,
            allow = 1
    )
    private List<BogeyCategory<?>> dimblend$filterMainCategories(List<BogeyCategory<?>> original) {
        if (!dimblend$restricted()) {
            return original;
        }
        return SimurailSurvivalBogeyGlue.filterMainCategories(original, this.inverted);
    }

    @ModifyExpressionValue(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/api/bogey/menu/BogeyMenuManager;findEntry"),
            require = 1,
            allow = 1
    )
    private BogeyMenuSelection dimblend$restrictSelection(BogeyMenuSelection original) {
        if (!dimblend$restricted()) {
            return original;
        }
        boolean allowed = original.entry().isPresent()
                && SimurailSurvivalBogeyRules.isAllowedEntry(original.entry().get().id());
        if (allowed) {
            return original;
        }
        return SimurailSurvivalBogeyGlue.allowedSelection(this.inverted);
    }

    @Redirect(
            method = "changeMainCategory",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/List;get(I)Ljava/lang/Object;"),
            require = 1,
            allow = 1
    )
    private Object dimblend$reuseFilteredCategories(List<?> ignored, int index) {
        return this.mainCategories.get(index);
    }

    @Unique
    private static boolean dimblend$restricted() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && !minecraft.player.isCreative();
    }
}
