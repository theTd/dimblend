package dimblend.craft.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** 可选森罗配方兼容门控；本模块不依赖 experience。 */
public final class MillstoneRecipeMixinPlugin implements IMixinConfigPlugin {

    /**
     * 与森罗无关、任何整合包下都要应用的 mixin：客户端显示名覆盖（2026-10-31 R12）。
     * 其余 mixin 只在森罗厨房存在时应用（上游类缺失时不加载对应 mixin）。
     */
    private static final Set<String> ALWAYS_APPLIED = Set.of(
            "dimblend.craft.mixin.client.LanguageOverrideMixin",
            "dimblend.craft.mixin.client.I18nAccessor");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (ALWAYS_APPLIED.contains(mixinClassName)) {
            return true;
        }
        return net.neoforged.fml.loading.FMLLoader.getLoadingModList()
                .getModFileById("kaleidoscope_cookery") != null;
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
