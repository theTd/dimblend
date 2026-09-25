package dimblend.blocks.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

/**
 * 按目标模组存在性过滤 mixin：第三方目标类缺失时该 mixin 不应用，
 * 保证"单独安装本模组不崩"（横切约定）。映射：mixin 类名 → 必须在场的模组 id。
 * 未列出的 mixin（原版目标）无条件应用。
 */
public class DimBlendBlocksMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".compat.copycats.CopycatsObsidianHardnessMixin")) {
            return isLoaded("copycats");
        }
        if (mixinClassName.endsWith(".compat.create.CreateCopycatObsidianHardnessMixin")) {
            return isLoaded("create");
        }
        return true;
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
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }
}
