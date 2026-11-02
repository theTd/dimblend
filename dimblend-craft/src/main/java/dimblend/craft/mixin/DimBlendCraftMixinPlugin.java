package dimblend.craft.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * {@code dimblend_craft.mixins.json} 的总门控。
 *
 * <p>一个 mixin 配置只能声明一个 {@code plugin}，而本模块原本用它挂
 * {@link MillstoneRecipeMixinPlugin}（森罗兼容）。拆解台那条新增的暮色森林兼容
 * 需要自己的在场性判断，于是把入口收叙到这里：本类负责「整份配置」的门控，
 * 森罗部分原样转交给 {@link MillstoneRecipeMixinPlugin}，避免同一套规则写两份后漂移。
 *
 * <p>两个约束要知道：
 * <ul>
 *   <li>本类在 mixin 的 {@code package} 内，但它不是 mixin 类（没有 {@code @Mixin}），
 *       所以不适用「成员一律 private / 实例方法」的限制 —— 同包的
 *       {@link MillstoneRecipeMixinPlugin} 也用 {@code private static final} 字段。</li>
 *   <li>转交是组合而不是继承：{@link MillstoneRecipeMixinPlugin} 是 {@code final} 的。</li>
 * </ul>
 */
public final class DimBlendCraftMixinPlugin implements IMixinConfigPlugin {

    /** 森罗侧门控的唯一来源；本类只做跳转，不复制它的规则。 */
    private final MillstoneRecipeMixinPlugin kaleidoscope = new MillstoneRecipeMixinPlugin();

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 拆解台清空只对暮色森林有意义。TF 缺席时目标类不存在，mixin 必须不应用，
        // 否则类加载即失败（本模块不声明 TF 依赖，任何整合包都可能没有它）。
        if (mixinClassName.endsWith(".compat.twilightforest.UncraftingMenuMixin")) {
            return isLoaded("twilightforest");
        }
        return kaleidoscope.shouldApplyMixin(targetClassName, mixinClassName);
    }

    private static boolean isLoaded(String modId) {
        return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}